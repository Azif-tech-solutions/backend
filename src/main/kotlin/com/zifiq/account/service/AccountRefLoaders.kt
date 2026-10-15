package com.zifiq.account.service

import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.common.tax.HsnSectionsTable
import com.zifiq.common.tax.IsicSectionsTable
import com.zifiq.common.tax.VatCategoriesTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How references to the finance records (and the tax classification account administers) appear in responses (docs/03 §5.1.2). */
class AccountRefLoaders {
    @Component
    class BankAccounts : TableRefLoader(RefKind.BANK_ACCOUNT, BankAccountsTable,
        "code" to BankAccountsTable.code, "bankName" to BankAccountsTable.bankName, "accountName" to BankAccountsTable.accountName)

    @Component
    class CashAccounts : TableRefLoader(RefKind.CASH_ACCOUNT, CashAccountsTable, "code" to CashAccountsTable.code, "name" to CashAccountsTable.name, "kind" to CashAccountsTable.kind)

    @Component
    class ExpenseCategories : TableRefLoader(RefKind.EXPENSE_CATEGORY, ExpenseCategoriesTable, "code" to ExpenseCategoriesTable.code, "name" to ExpenseCategoriesTable.name)

    @Component
    class Receipts : TableRefLoader(RefKind.RECEIPT, PaymentReceiptsTable, "ref" to PaymentReceiptsTable.ref, "kind" to PaymentReceiptsTable.kind)

    @Component
    class VatCategories : TableRefLoader(RefKind.VAT_CATEGORY, VatCategoriesTable, "code" to VatCategoriesTable.code, "name" to VatCategoriesTable.name, "rate" to VatCategoriesTable.rate)

    @Component
    class HsnSections : TableRefLoader(RefKind.HSN_SECTION, HsnSectionsTable, "code" to HsnSectionsTable.code, "name" to HsnSectionsTable.name)

    @Component
    class IsicSections : TableRefLoader(RefKind.ISIC_SECTION, IsicSectionsTable, "code" to IsicSectionsTable.code, "name" to IsicSectionsTable.name)
}
