package com.zifiq.platform.directory

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.zifiq.platform.identity.model.AppUsersTable
import io.swagger.v3.oas.annotations.media.Schema
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * A user a response mentions — who recorded, approved, issued, uploaded… — always returned as
 * `{ "id", "name", "email" }`, never as a bare id or a bare name. Services only write `UserRef(id)`;
 * [UserRefResolver] fills in every reference in a response with one query just before it is
 * written, so a page of 50 rows naming 50 users is still one lookup.
 */
@JsonSerialize(using = UserRefSerializer::class)
@Schema(description = "A user: { id, name, email }", example = """{"id":"3f0c…","name":"Alice Admin","email":"alice@apex.ng"}""")
class UserRef(val id: UUID) {
    @Volatile
    internal var details: Details? = null

    internal class Details(val name: String, val email: String)

    /** For server-side rendering (PDF templates), after [UserRefs.resolve]; null until resolved. */
    val name: String? get() = details?.name
    val email: String? get() = details?.email

    override fun equals(other: Any?) = other is UserRef && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "UserRef($id)"

    companion object {
        /** Null-safe: a nullable user id column becomes a nullable reference. */
        fun of(id: UUID?): UserRef? = id?.let(::UserRef)
    }
}

class UserRefSerializer : StdSerializer<UserRef>(UserRef::class.java) {
    override fun serialize(value: UserRef, generator: JsonGenerator, provider: SerializerProvider) {
        val details = value.details
        generator.writeStartObject()
        generator.writeStringField("id", value.id.toString())
        generator.writeStringField("name", details?.name)
        generator.writeStringField("email", details?.email)
        generator.writeEndObject()
    }
}

/**
 * Resolves every [UserRef] in an API response before it is written: one walk over the response
 * (data classes, lists, maps, pages) and one primary-key query on app_users for all of them.
 * app_users is platform-wide (no tenant column); a reference only ever comes from the caller's own
 * tenant-scoped rows.
 */
@RestControllerAdvice
class UserRefResolver(private val database: Database) : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?, returnType: MethodParameter, contentType: MediaType, converterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest, response: ServerHttpResponse,
    ): Any? {
        resolve(body)
        return body
    }

    private fun resolve(body: Any?) {
        val refs = UserRefs.unresolved(body)
        if (refs.isNotEmpty()) transaction(database) { UserRefs.fill(refs) }
    }
}

/** Finds the [UserRef]s in an object graph of our own types, collections and maps, and fills them in. */
object UserRefs {
    /**
     * Fills in every reference in [root] with one query, in the caller's transaction — for output the
     * HTTP resolver doesn't see (a PDF, an e-mail). API responses need nothing: [UserRefResolver] does it.
     */
    fun resolve(root: Any?) = fill(unresolved(root))

    internal fun unresolved(root: Any?): List<UserRef> = collect(root).filter { it.details == null }

    internal fun fill(refs: List<UserRef>) {
        if (refs.isEmpty()) return
        val details = AppUsersTable.select(AppUsersTable.id, AppUsersTable.displayName, AppUsersTable.email)
            .where { AppUsersTable.id inList refs.map { it.id }.toSet() }
            .associate { it[AppUsersTable.id].value to UserRef.Details(it[AppUsersTable.displayName], it[AppUsersTable.email]) }
        refs.forEach { it.details = details[it.id] }
    }

    private val fieldsByClass = ConcurrentHashMap<Class<*>, List<Field>>()

    internal fun collect(root: Any?): List<UserRef> {
        val found = mutableListOf<UserRef>()
        val seen = IdentityHashMap<Any, Unit>()
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > MAX_DEPTH || seen.put(value, Unit) != null) return
            when (value) {
                is UserRef -> found += value
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
