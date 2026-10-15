package com.zifiq.platform.directory

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import io.swagger.v3.oas.annotations.media.Schema
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Every kind of record a response can point at. Each is resolved by the [RefLoader] its owning
 * module registers (users, locations and roles by platform).
 */
enum class RefKind {
    USER, LOCATION, ROLE,
    STOCK_ITEM, UNIT, CATEGORY, SUPPLIER, MANUFACTURER, DRIVER, STOCK_TRANSFER,
    VAT_CATEGORY, HSN_SECTION, ISIC_SECTION,
    CUSTOMER, CORRESPONDENT, DEALER_CATEGORY,
    BANK_ACCOUNT, CASH_ACCOUNT, EXPENSE_CATEGORY, RECEIPT,
    INVOICE,
    RECIPE, SPECIAL_PRODUCTION_CLIENT,
}

/**
 * A record a response points at — a stock item, a unit, a location, a customer, a user… — always
 * returned with its identifying details (`{ "id", "code", "name", … }`), never as a bare id.
 * Services only write `Ref.stockItem(id)`; [RefResolver] fills in every reference in a response
 * just before it is written, with one query per kind of record, so a page of 50 rows naming 50
 * stock items and 50 locations is two lookups. Request bodies keep plain ids: a client says which
 * record it means by id.
 */
@JsonSerialize(using = RefSerializer::class)
@Schema(description = "A referenced record: its id plus identifying details (e.g. code, name)")
open class Ref(val kind: RefKind, val id: UUID) {
    @Volatile
    internal var details: Map<String, Any?>? = null

    /** A detail of the record (e.g. `"name"`), after resolution; null until resolved or for an unknown record. */
    operator fun get(field: String): Any? = details?.get(field)

    /** The record's display name and code, for server-side rendering (PDF templates); null until resolved. */
    val name: String? get() = get("name") as String?
    val code: String? get() = get("code") as String?

    /** Every detail, for templates that need another one (`ref.fields['bankName']`); empty until resolved. */
    val fields: Map<String, Any?> get() = details.orEmpty()

    override fun equals(other: Any?) = other is Ref && other.kind == kind && other.id == id
    override fun hashCode() = 31 * kind.hashCode() + id.hashCode()
    override fun toString() = "Ref($kind, $id)"

    companion object {
        fun user(id: UUID) = UserRef(id)
        fun location(id: UUID) = Ref(RefKind.LOCATION, id)
        fun role(id: UUID) = Ref(RefKind.ROLE, id)
        fun stockItem(id: UUID) = Ref(RefKind.STOCK_ITEM, id)
        fun unit(id: UUID) = Ref(RefKind.UNIT, id)
        fun category(id: UUID) = Ref(RefKind.CATEGORY, id)
        fun supplier(id: UUID) = Ref(RefKind.SUPPLIER, id)
        fun manufacturer(id: UUID) = Ref(RefKind.MANUFACTURER, id)
        fun driver(id: UUID) = Ref(RefKind.DRIVER, id)
        fun stockTransfer(id: UUID) = Ref(RefKind.STOCK_TRANSFER, id)
        fun vatCategory(id: UUID) = Ref(RefKind.VAT_CATEGORY, id)
        fun hsnSection(id: UUID) = Ref(RefKind.HSN_SECTION, id)
        fun isicSection(id: UUID) = Ref(RefKind.ISIC_SECTION, id)
        fun customer(id: UUID) = Ref(RefKind.CUSTOMER, id)
        fun correspondent(id: UUID) = Ref(RefKind.CORRESPONDENT, id)
        fun dealerCategory(id: UUID) = Ref(RefKind.DEALER_CATEGORY, id)
        fun bankAccount(id: UUID) = Ref(RefKind.BANK_ACCOUNT, id)
        fun cashAccount(id: UUID) = Ref(RefKind.CASH_ACCOUNT, id)
        fun expenseCategory(id: UUID) = Ref(RefKind.EXPENSE_CATEGORY, id)
        fun receipt(id: UUID) = Ref(RefKind.RECEIPT, id)
        fun invoice(id: UUID) = Ref(RefKind.INVOICE, id)
        fun recipe(id: UUID) = Ref(RefKind.RECIPE, id)
        fun specialProductionClient(id: UUID) = Ref(RefKind.SPECIAL_PRODUCTION_CLIENT, id)
    }
}

/** A user a response mentions: `{ "id", "name", "email" }`. */
@JsonSerialize(using = RefSerializer::class)
@Schema(description = "A user: { id, name, email }", example = """{"id":"3f0c…","name":"Alice Admin","email":"alice@apex.ng"}""")
class UserRef(id: UUID) : Ref(RefKind.USER, id) {
    val email: String? get() = get("email") as String?

    companion object {
        /** Null-safe: a nullable user id column becomes a nullable reference. */
        fun of(id: UUID?): UserRef? = id?.let(::UserRef)
    }
}

/** Null-safe constructor for any kind: `ref(row[column], Ref::location)`. */
fun <R : Ref> ref(id: UUID?, of: (UUID) -> R): R? = id?.let(of)

class RefSerializer : StdSerializer<Ref>(Ref::class.java) {
    override fun serialize(value: Ref, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeStringField("id", value.id.toString())
        (value.details ?: Refs.emptyDetails(value.kind)).forEach { (field, detail) -> provider.defaultSerializeField(field, detail, generator) }
        generator.writeEndObject()
    }
}

/**
 * Loads the identifying details of one kind of record, for many ids in one query, in the caller's
 * transaction (row-level security applies). Each module registers one per kind it owns.
 */
interface RefLoader {
    val kind: RefKind

    /** The detail fields, in output order (also written, as nulls, for a record that can't be found). */
    val fields: List<String>

    fun load(ids: Set<UUID>): Map<UUID, Map<String, Any?>>
}

/** A [RefLoader] that reads [columns] from a table by primary key — what almost every kind needs. */
open class TableRefLoader(
    override val kind: RefKind,
    private val table: IdTable<UUID>,
    private vararg val columns: Pair<String, Column<*>>,
) : RefLoader {
    override val fields: List<String> = columns.map { it.first }

    override fun load(ids: Set<UUID>): Map<UUID, Map<String, Any?>> =
        table.select(listOf(table.id) + columns.map { it.second }).where { table.id inList ids }
            .associate { row -> row[table.id].value to columns.associate { (field, column) -> field to row[column] } }
}

/** Users, locations and roles: the records platform owns. */
class PlatformRefLoaders {
    @Component
    class Users : TableRefLoader(RefKind.USER, AppUsersTable, "name" to AppUsersTable.displayName, "email" to AppUsersTable.email)

    @Component
    class Locations : TableRefLoader(RefKind.LOCATION, LocationsTable, "code" to LocationsTable.code, "name" to LocationsTable.name, "type" to LocationsTable.type)

    @Component
    class Roles : TableRefLoader(RefKind.ROLE, TenantRolesTable, "name" to TenantRolesTable.name)
}

/**
 * The registry of loaders and the resolution itself. Loaders register at startup (every
 * [RefLoader] bean); users are built in, so code that runs without Spring (tests) still resolves them.
 */
object Refs {
    private val loaders = ConcurrentHashMap<RefKind, RefLoader>(mapOf(RefKind.USER to PlatformRefLoaders.Users()))

    fun register(loader: RefLoader) {
        loaders[loader.kind] = loader
    }

    /**
     * Fills in every reference in [root] — one query per kind — in the caller's transaction. API
     * responses need nothing ([RefResolver] does it); call this for output the HTTP layer doesn't
     * see (a PDF, an e-mail) and read `ref.name`.
     */
    fun resolve(root: Any?) = fill(unresolved(root))

    internal fun unresolved(root: Any?): List<Ref> = collect(root).filter { it.details == null }

    internal fun fill(refs: List<Ref>) {
        refs.groupBy { it.kind }.forEach { (kind, ofKind) ->
            val loader = loaders[kind] ?: return@forEach
            val found = loader.load(ofKind.map { it.id }.toSet())
            ofKind.forEach { it.details = found[it.id] ?: emptyDetails(kind) }
        }
    }

    internal fun emptyDetails(kind: RefKind): Map<String, Any?> = loaders[kind]?.fields.orEmpty().associateWith { null }

    private val fieldsByClass = ConcurrentHashMap<Class<*>, List<Field>>()

    internal fun collect(root: Any?): List<Ref> {
        val found = mutableListOf<Ref>()
        val seen = IdentityHashMap<Any, Unit>()
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > MAX_DEPTH || seen.put(value, Unit) != null) return
            when (value) {
                is Ref -> found += value
                is Iterable<*> -> value.forEach { visit(it, depth + 1) }
                is Map<*, *> -> value.values.forEach { visit(it, depth + 1) }
                is Array<*> -> value.forEach { visit(it, depth + 1) }
                is Function<*> -> Unit
                else -> if (value.javaClass.name.startsWith(OUR_PACKAGE) && !value.javaClass.isEnum) {
                    fields(value.javaClass).forEach { visit(it.get(value), depth + 1) }
                }
            }
        }
        visit(root, 0)
        return found
    }

    private fun fields(type: Class<*>): List<Field> = fieldsByClass.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it.name.startsWith(OUR_PACKAGE) }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.type.isPrimitive && it.type != String::class.java }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private const val OUR_PACKAGE = "com.zifiq."
    private const val MAX_DEPTH = 16
}

/**
 * Resolves every [Ref] in an API response before it is written: one walk over the response (data
 * classes, lists, maps, pages) and one query per kind of record. Tenant records are read in the
 * request's tenant transaction, so row-level security applies exactly as for the response itself.
 */
@RestControllerAdvice
class RefResolver(
    private val database: Database,
    private val tenantContext: TenantContext,
    private val tx: TenantScopedTransaction,
    loaders: List<RefLoader>,
) : ResponseBodyAdvice<Any> {
    init {
        loaders.forEach(Refs::register)
    }

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?, returnType: MethodParameter, contentType: MediaType, converterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest, response: ServerHttpResponse,
    ): Any? {
        val refs = Refs.unresolved(body)
        if (refs.isEmpty()) return body
        val inTenant = runCatching { tenantContext.requireTenantId() }.isSuccess
        if (inTenant) tx.execute { Refs.fill(refs) } else transaction(database) { Refs.fill(refs) }
        return body
    }
}
