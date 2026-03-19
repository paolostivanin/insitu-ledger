package com.insituledger.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DescriptionSuggestion(
    val description: String,
    val categoryId: Long
)

data class AccountDisplay(
    val account: Account,
    val label: String
)

data class TransactionFormUiState(
    val id: Long? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val type: String = "expense",
    val amount: String = "",
    val currency: String = "EUR",
    val description: String = "",
    val date: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val accounts: List<Account> = emptyList(),
    val accountDisplays: List<AccountDisplay> = emptyList(),
    val categories: List<Category> = emptyList(),
    val suggestions: List<DescriptionSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val sharedAccessState: SharedAccessState,
    private val prefs: UserPreferences
) : ViewModel() {

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val _uiState = MutableStateFlow(TransactionFormUiState(id = editId))
    val uiState: StateFlow<TransactionFormUiState> = _uiState.asStateFlow()

    init { loadData() }

    private fun loadData() {
        viewModelScope.launch {
            val owner = sharedAccessState.selectedOwner.value
            val isShared = owner != null

            val accounts: List<Account>
            val categories: List<Category>

            if (isShared) {
                accounts = accountRepository.listFromServer(owner!!.ownerId)
                categories = categoryRepository.listFromServer(owner.ownerId)
            } else {
                accounts = accountRepository.getAll().first()
                categories = categoryRepository.getAll().first()
            }

            val displays = accounts.map { acct ->
                AccountDisplay(
                    account = acct,
                    label = if (isShared) "${acct.name} (shared)" else acct.name
                )
            }

            _uiState.update { it.copy(accounts = accounts, accountDisplays = displays, categories = categories) }

            if (editId != null) {
                val txn = transactionRepository.getById(editId)
                if (txn != null) {
                    _uiState.update {
                        it.copy(
                            accountId = txn.accountId, categoryId = txn.categoryId,
                            type = txn.type, amount = txn.amount.toString(),
                            currency = txn.currency, description = txn.description ?: "",
                            date = txn.date, isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } else {
                // Default to last-used account
                val lastAccountId = prefs.lastUsedAccountIdFlow.first()
                val defaultAccount = if (lastAccountId != null && accounts.any { it.id == lastAccountId }) {
                    lastAccountId
                } else {
                    accounts.firstOrNull()?.id
                }
                _uiState.update {
                    it.copy(
                        accountId = defaultAccount,
                        currency = accounts.find { a -> a.id == defaultAccount }?.currency ?: "EUR",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateAccountId(id: Long) {
        _uiState.update { it.copy(accountId = id) }
        viewModelScope.launch { prefs.saveLastUsedAccountId(id) }
    }
    fun updateCategoryId(id: Long) { _uiState.update { it.copy(categoryId = id) } }
    fun updateType(type: String) { _uiState.update { it.copy(type = type) } }
    fun updateAmount(amount: String) { _uiState.update { it.copy(amount = amount) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    private var autocompleteJob: Job? = null

    fun updateDescription(desc: String) {
        _uiState.update { it.copy(description = desc) }
        autocompleteJob?.cancel()
        if (desc.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(200)
            try {
                val results = transactionRepository.autocomplete(desc)
                _uiState.update {
                    it.copy(
                        suggestions = results.map { (d, c) -> DescriptionSuggestion(d, c) },
                        showSuggestions = results.isNotEmpty()
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
            }
        }
    }

    fun selectSuggestion(suggestion: DescriptionSuggestion) {
        _uiState.update {
            it.copy(
                description = suggestion.description,
                categoryId = suggestion.categoryId,
                suggestions = emptyList(),
                showSuggestions = false
            )
        }
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(showSuggestions = false) }
    }

    fun updateDate(date: String) { _uiState.update { it.copy(date = date) } }

    fun createCategory(name: String, type: String) {
        viewModelScope.launch {
            val id = categoryRepository.create(name, type, null, null, null)
            val owner = sharedAccessState.selectedOwner.value
            val updatedCategories = if (owner != null) {
                categoryRepository.listFromServer(owner.ownerId)
            } else {
                categoryRepository.getAll().first()
            }
            _uiState.update { it.copy(categories = updatedCategories, categoryId = id) }
        }
    }

    fun save() {
        val state = _uiState.value
        val amount = state.amount.toDoubleOrNull()
        if (state.accountId == null || state.categoryId == null || amount == null || amount <= 0) {
            _uiState.update { it.copy(error = "Please fill all required fields with valid values") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                // Persist last-used account
                prefs.saveLastUsedAccountId(state.accountId)

                if (editId != null) {
                    transactionRepository.update(
                        editId, state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null }, state.date
                    )
                } else {
                    transactionRepository.create(
                        state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null }, state.date
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
