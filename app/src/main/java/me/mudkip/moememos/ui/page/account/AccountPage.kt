package me.mudkip.moememos.ui.page.account

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.onSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.MemosAccount
import me.mudkip.moememos.ext.popBackStackIfLifecycleIsResumed
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.page.common.RouteName
import me.mudkip.moememos.data.service.AccountExportStage
import me.mudkip.moememos.viewmodel.AccountViewModel
import me.mudkip.moememos.viewmodel.AccountExportKind
import me.mudkip.moememos.viewmodel.AccountExportTaskState
import me.mudkip.moememos.viewmodel.LocalUserState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPage(
    navController: NavHostController,
    selectedAccountKey: String
) {
    val viewModel = hiltViewModel<AccountViewModel, AccountViewModel.AccountViewModelFactory> { factory ->
        factory.create(selectedAccountKey)
    }
    val userStateViewModel = LocalUserState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val selectedAccount by viewModel.selectedAccountState.collectAsState()
    val exportTaskState by viewModel.exportTaskState.collectAsState()
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val memosAccount = selectedAccount.toMemosAccount()
    val isLocalAccount = selectedAccountKey == Account.Local().accountKey() || selectedAccount is Account.Local
    val showSwitchAccountButton = selectedAccountKey != currentAccount?.accountKey()
    val coroutineScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            val result = viewModel.exportLocalAccount(uri)
            result.onSuccess {
                Toast.makeText(navController.context, R.string.local_export_success.string, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                val message = error.localizedMessage ?: R.string.local_export_failed.string
                Toast.makeText(navController.context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val exportForKeerLauncher = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            val result = viewModel.exportSelectedAccountForKeer(uri)
            result.onSuccess {
                Toast.makeText(navController.context, R.string.keer_export_success.string, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                val message = error.localizedMessage ?: R.string.keer_export_failed.string
                Toast.makeText(navController.context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = R.string.account_detail.string) },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                    }
                },
            )
        },
    ) { innerPadding ->
        if (isLocalAccount) {
            LocalAccountPage(
                innerPadding = innerPadding,
                showSwitchAccountButton = showSwitchAccountButton,
                onSwitchAccount = {
                    coroutineScope.launch {
                        userStateViewModel.switchAccount(selectedAccountKey)
                            .onSuccess {
                                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                            }
                    }
                },
                onExportLocalAccount = {
                    val filename = "MoeMemos-Export-${exportTimestamp(Instant.now())}.zip"
                    exportLauncher.launch(filename)
                }
            )
        } else if (memosAccount != null) {
            MemosAccountPage(
                innerPadding = innerPadding,
                account = memosAccount,
                profile = viewModel.instanceProfile,
                okHttpClient = userStateViewModel.okHttpClient,
                showSwitchAccountButton = showSwitchAccountButton,
                onSwitchAccount = {
                    coroutineScope.launch {
                        userStateViewModel.switchAccount(selectedAccountKey)
                            .onSuccess {
                                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                            }
                    }
                },
                onExportForKeer = {
                    val filename = "MoeMemos-To-Keer-${exportTimestamp(Instant.now())}.zip"
                    exportForKeerLauncher.launch(filename)
                },
                onSignOut = {
                    coroutineScope.launch {
                        userStateViewModel.logout(selectedAccountKey)
                        if (userStateViewModel.currentAccount.first() == null) {
                            navController.navigate(RouteName.ADD_ACCOUNT) {
                                popUpTo(navController.graph.id) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                        }
                    }
                }
            )
        } else {
            LazyColumn(contentPadding = innerPadding) {}
        }
    }

    LaunchedEffect(selectedAccountKey) {
        viewModel.loadInstanceProfile()
    }

    if (exportTaskState.running) {
        AccountExportProgressDialog(
            taskState = exportTaskState
        )
    }
}

private fun exportTimestamp(instant: Instant): String {
    return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

private fun Account?.toMemosAccount(): MemosAccount? = when (this) {
    is Account.MemosV0 -> info
    is Account.MemosV1 -> info
    else -> null
}

@Composable
private fun AccountExportProgressDialog(taskState: AccountExportTaskState) {
    val titleText = when (taskState.kind) {
        AccountExportKind.LOCAL -> R.string.export_local_account.string
        AccountExportKind.KEER -> R.string.export_for_keer.string
        null -> R.string.loading.string
    }
    val stageText = when (taskState.stage) {
        AccountExportStage.PREPARING -> R.string.export_progress_preparing.string
        AccountExportStage.PROCESSING_MEMOS -> R.string.export_progress_processing_memos.string
        AccountExportStage.PROCESSING_ATTACHMENTS -> R.string.export_progress_processing_attachments.string
        AccountExportStage.WRITING_OUTPUT -> R.string.export_progress_writing_output.string
        AccountExportStage.COMPLETED -> R.string.export_progress_completed.string
        null -> R.string.loading.string
    }
    val progress = taskState.total?.takeIf { it > 0 }?.let { total ->
        val current = (taskState.completed ?: 0).coerceIn(0, total)
        current.toFloat() / total.toFloat()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = androidx.compose.ui.Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = stageText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(bottom = 14.dp)
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(progress * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = androidx.compose.ui.Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
