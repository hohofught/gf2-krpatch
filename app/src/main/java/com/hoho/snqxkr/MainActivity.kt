package com.hoho.snqxkr

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private var vm: PatchViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Shizuku 공식 예제대로 onCreate 에서 등록, onDestroy 에서 해제
        ShizukuBridge.addListeners()
        setContent {
            SnqxKRTheme {
                val model: PatchViewModel = viewModel()
                vm = model
                LaunchedEffect(Unit) { openFromNotice(intent) }
                PatchScreen(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotice(intent)
    }

    /** 알림을 탭해서 들어오면 그 클라이언트를 보여준다 */
    private fun openFromNotice(intent: Intent?) {
        intent?.getStringExtra(Notices.EXTRA_PKG)?.let { vm?.selectGame(it) }
    }

    override fun onStart() {
        super.onStart()
        ShizukuBridge.uiVisible = true
    }

    override fun onResume() {
        super.onResume()
        vm?.refresh()
        vm?.onNotificationPermission()
    }

    override fun onStop() {
        super.onStop()
        ShizukuBridge.uiVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuBridge.removeListeners()
        if (isFinishing) ShizukuBridge.unbind()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(vm: PatchViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    LaunchedEffect(ui.snack) {
        ui.snack?.let {
            snackbar.showSnackbar(it)
            vm.dismissSnack()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("소전2 한글패치") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = inner.calculateTopPadding() + 4.dp,
                bottom = inner.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.installed.size > 1) item { ClientSelector(ui, vm) }
            item { StatusHero(ui) }
            // Shizuku 가 준비되기 전에는 적용 버튼 대신 준비 단계를 보여준다
            item { if (ui.shizuku == ShizukuState.READY) ActionButtons(ui, vm) else SetupCard(ui, vm) }
            item { GameCard(ui) }
            item { PatchFileCard(ui, vm) }
            item { SettingsCard(ui, vm) }
            item { MaintenanceCard(ui, vm) }
            item { StorageCard(ui, vm) }
            item { LogCard(ui) }
            item {
                Text(
                    "패치 원본: nemasdf/haguel-baefo · 중섭(官服·B服·QQ) 전용, 글로벌·한국 서버 클라이언트는 건드리지 않습니다 · " +
                        "게임을 완전히 종료한 상태에서 적용하세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private data class HeroLook(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val container: Color,
    val onContainer: Color,
)

@Composable
private fun StatusHero(ui: UiState) {
    val cs = MaterialTheme.colorScheme
    val look = when {
        ui.busy -> HeroLook(
            Icons.Rounded.Bolt, ui.busyLabel.ifEmpty { "작업 중" },
            ui.progressText.ifEmpty { "잠시만 기다려 주세요" }, cs.secondaryContainer, cs.onSecondaryContainer
        )
        ui.status == PatchStatus.NO_GAME -> HeroLook(
            Icons.Rounded.ErrorOutline, "게임 미설치",
            "소녀전선2: 망명 중섭(官服·B服·QQ) 클라이언트를 찾지 못했습니다", cs.errorContainer, cs.onErrorContainer
        )
        ui.shizuku != ShizukuState.READY -> {
            val (title, body) = when (ui.shizuku) {
                ShizukuState.NOT_INSTALLED -> "Shizuku 설치 필요" to "게임 폴더에 쓰려면 Shizuku 가 필요합니다"
                ShizukuState.NOT_RUNNING -> "Shizuku 실행 필요" to "Shizuku 앱에서 서비스를 시작하세요"
                ShizukuState.OUTDATED -> "Shizuku 업데이트 필요" to "Shizuku v11 이상이 필요합니다"
                ShizukuState.NO_PERMISSION -> "권한 허용 필요" to "아래 버튼으로 이 앱에 Shizuku 권한을 허용하세요"
                else -> "Shizuku 에서 허용 필요" to "권한이 '다시 묻지 않음'으로 거부돼 있습니다"
            }
            HeroLook(Icons.Rounded.Security, title, body, cs.tertiaryContainer, cs.onTertiaryContainer)
        }
        // 게임 업데이트로 한패가 풀렸고, 받아 둔 한패는 아직 옛 버전용
        ui.status == PatchStatus.OFFICIAL && ui.patchMatchesGame == false -> HeroLook(
            Icons.Rounded.Warning, "게임 업데이트로 한글패치가 풀렸습니다",
            if (ui.remotePending && ui.canRepair) "서버에 새 한패가 올라왔습니다. 받아서 이 게임 버전용이면 바로 넣고, 아직 아니면 임시 복구합니다"
            else if (ui.remotePending) "서버에 새 한패가 올라왔습니다. 받아서 이 게임 버전용이면 바로 넣습니다"
            else if (ui.canRepair) "새 한패가 나오기 전까지 임시 복구로 쓸 수 있습니다. 새로 생긴 문장만 중국어로 남습니다"
            else "새 한패를 기다리는 중입니다. 번역 메모리가 있으면 임시 복구할 수 있습니다",
            cs.errorContainer, cs.onErrorContainer
        )
        ui.status == PatchStatus.REPAIRED && ui.patchMatchesGame == true -> HeroLook(
            Icons.Rounded.Update, "정식 한글패치가 나왔습니다",
            "임시 복구본을 정식 한패로 바꾸세요", cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.REPAIRED -> HeroLook(
            Icons.Rounded.AutoFixHigh, "임시 복구 적용됨",
            (if (ui.repairedCoverage >= 0) "한국어 " + PatchViewModel.percent(ui.repairedCoverage.toDouble()) + " · " else "") +
                if (ui.remotePending) "서버에 새 한패가 올라왔습니다. 받아서 정식 한패면 바로 바꿉니다"
                else "정식 한패를 기다리는 중 · 확인 " + PatchViewModel.time(ui.checkedAt),
            cs.secondaryContainer, cs.onSecondaryContainer
        )
        ui.status == PatchStatus.FOREIGN && ui.patchMatchesGame == false -> HeroLook(
            Icons.Rounded.Warning, "게임 버전과 맞지 않는 한글패치",
            "게임 업데이트 전 한패가 들어 있어 문장이 엉뚱하게 나올 수 있습니다. 게임 버전에 맞는 한패가 나오면 적용하세요",
            cs.errorContainer, cs.onErrorContainer
        )
        ui.status == PatchStatus.PATCHED_LATEST -> HeroLook(
            Icons.Rounded.CheckCircle, "최신 한글패치 적용됨",
            // 다른 앱(1.0 등)이나 손으로 넣은 경우엔 이 앱의 적용 기록이 없다
            (if (ui.appliedAt > 0) "적용 " + PatchViewModel.time(ui.appliedAt) + " · " else "게임 폴더 파일이 최신 한패와 같습니다 · ") +
                "확인 " + PatchViewModel.time(ui.checkedAt),
            cs.primaryContainer, cs.onPrimaryContainer
        )
        ui.status == PatchStatus.PATCHED_OLD -> HeroLook(
            Icons.Rounded.Update, "업데이트 있음",
            "새 한패가 올라왔습니다. 적용을 눌러 갱신하세요", cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.OFFICIAL -> HeroLook(
            Icons.Rounded.Translate, "한글패치 미적용",
            "한글패치가 아직 적용되지 않았습니다. 게임 업데이트로 원본(중국어) 파일로 덮였을 수 있습니다",
            cs.surfaceVariant, cs.onSurfaceVariant
        )
        ui.status == PatchStatus.FOREIGN && ui.cacheSha.isEmpty() -> HeroLook(
            Icons.Rounded.Update, "한글패치 적용됨 · 확인 필요",
            "최신 한패인지 아직 확인하지 않았습니다. '업데이트만 확인'을 눌러 주세요",
            cs.secondaryContainer, cs.onSecondaryContainer
        )
        ui.status == PatchStatus.FOREIGN -> HeroLook(
            Icons.Rounded.Warning, "이전 버전 한글패치",
            "최신이 아닌 한글패치가 들어 있습니다. 적용을 누르면 최신 한패로 바꿉니다",
            cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.NOT_PATCHED -> HeroLook(
            Icons.Rounded.Translate, "한글패치 미적용",
            "아래 버튼 한 번이면 다운로드부터 적용까지 끝납니다", cs.surfaceVariant, cs.onSurfaceVariant
        )
        else -> HeroLook(
            Icons.AutoMirrored.Rounded.HelpOutline, "상태 확인 필요",
            "새로고침을 눌러 게임 폴더를 확인하세요", cs.surfaceVariant, cs.onSurfaceVariant
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.onContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = look.onContainer.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        look.icon, contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                        tint = look.onContainer,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(look.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(look.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            AnimatedVisibility(ui.busy) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    if (ui.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { ui.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(ui: UiState, vm: PatchViewModel) {
    // 큰 버튼: 지금 할 가장 좋은 일 하나 (PrimaryAction, 윈도우와 같은 규칙)
    val mismatch = ui.patchMatchesGame == false
    val action = PrimaryAction.decide(
        gameReady = true, repaired = ui.status == PatchStatus.REPAIRED, matches = ui.patchMatchesGame,
        canRepair = ui.canRepair, remotePending = ui.remotePending,
    )
    val primary: Triple<String, ImageVector, () -> Unit>? = action?.let { a ->
        when (a) {
            PrimaryAction.REPAIR -> Triple(a.label, Icons.Rounded.AutoFixHigh) { vm.repair() }
            PrimaryAction.UPDATE_APPLY, PrimaryAction.UPDATE_REPLACE -> Triple(a.label, Icons.Rounded.Download) { vm.updateThen() }
            PrimaryAction.CHECK -> Triple(a.label, Icons.Rounded.Refresh) { vm.checkUpdate() }
            PrimaryAction.APPLY, PrimaryAction.REPLACE -> Triple(a.label, Icons.Rounded.Download) { vm.downloadAndApply() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary != null) {
            Button(
                onClick = primary.third,
                enabled = !ui.busy && ui.gamePkg != null && ui.shizuku == ShizukuState.READY,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(primary.second, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(primary.first, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (ui.patchMisaligned) {
            Notice(
                "새로 올라온 한패는 이 게임 버전용이지만 문장 자리가 원문과 맞지 않습니다 " +
                    "(번역 안 된 줄 중 ${PatchViewModel.percent(ui.alignRatio.coerceAtLeast(0.0))} 일치). " +
                    "한패 관리자가 고칠 때까지 넣지 않고 임시 복구를 씁니다."
            )
            // 넣게 될 한패가 무엇인지 버튼에 적는다: 받은 시각, 원문과 자리 일치율
            val patchInfo = listOfNotNull(
                if (ui.patchReceivedAt > 0) PatchViewModel.time(ui.patchReceivedAt) + " 받음" else null,
                "원문과 자리 일치 " + PatchViewModel.percent(ui.alignRatio.coerceAtLeast(0.0)),
            ).joinToString(" · ")
            TextButton(
                onClick = { vm.forceApply() },
                enabled = !ui.busy && ui.shizuku == ShizukuState.READY,
            ) { Text("그래도 이 한패 적용 ($patchInfo)") }
        } else if (mismatch && !ui.canRepair && ui.status != PatchStatus.REPAIRED) {
            Notice(
                "받아 둔 한패는 이전 게임 버전용이라 넣지 않습니다. " +
                    if (ui.canBootstrap) "아래 '번역 메모리 준비'를 하면 임시 복구할 수 있습니다." else "새 한패가 나오면 알려드립니다."
            )
        }
        if (ui.remotePending) Notice("새 한패가 올라왔습니다 (아직 받지 않음). 큰 버튼이나 '업데이트만 확인'을 누르면 받습니다.")
        // 큰 버튼이 "새 한패 확인" 이면 같은 일을 하는 버튼은 숨긴다
        if (action != PrimaryAction.CHECK) OutlinedButton(
            onClick = { vm.checkUpdate() },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Update, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("업데이트만 확인")
        }
        if (ui.gameRunning) {
            Text(
                "게임이 실행 중입니다. 최근 앱에서 완전히 종료한 뒤 적용하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GameCard(ui: UiState) {
    SectionCard(Icons.Rounded.SportsEsports, "게임") {
        Column {
            if (ui.gamePkg == null) {
                Text(
                    "설치된 소전2 중섭 클라이언트가 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoRow("클라이언트", ui.gameLabel)
                InfoRow("패키지", ui.gamePkg, mono = true)
                InfoRow("버전", ui.gameVersion.ifEmpty { "-" })
                InfoRow("상태", if (ui.gameRunning) "실행 중" else "종료됨")
                if (ui.shizuku == ShizukuState.READY) InfoRow("접근 권한", accessLabel(ui.privilegedUid))
                InfoRow("대상 경로", Paths.tableDir(ui.gamePkg), mono = true)
                if (ui.targetSize >= 0) {
                    InfoRow("현재 파일", PatchViewModel.human(ui.targetSize) + " · " + PatchViewModel.time(ui.targetTime))
                    InfoRow("SHA-256", ui.targetSha.take(16) + "…", mono = true)
                }
            }
        }
    }
}

private fun accessLabel(uid: Int): String = "Shizuku" + when (uid) {
    0 -> " · root"
    2000 -> " · shell (무선 디버깅)"
    -1 -> ""
    else -> " · uid $uid"
}

/** 설치된 중섭 클라이언트가 둘 이상일 때 官服·B服·QQ 중 고르기 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientSelector(ui: UiState, vm: PatchViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ui.installed.forEachIndexed { i, g ->
            SegmentedButton(
                selected = g.pkg == ui.gamePkg,
                onClick = { vm.selectGame(g.pkg) },
                enabled = !ui.busy,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = ui.installed.size),
            ) { Text(g.short) }
        }
    }
}

/**
 * Shizuku 준비 3단계. 지금 할 일 하나만 큰 버튼으로 보여준다.
 * 권한은 사용자가 버튼을 눌렀을 때만 요청한다 (앱을 열자마자 창을 띄우지 않는다).
 */
@Composable
private fun SetupCard(ui: UiState, vm: PatchViewModel) {
    val context = LocalContext.current
    val s = ui.shizuku
    val (actionLabel, action) = when (s) {
        ShizukuState.NOT_INSTALLED -> "Shizuku 설치하기" to { ShizukuBridge.openInstallPage(context) }
        ShizukuState.NOT_RUNNING -> "Shizuku 열어서 시작하기" to { ShizukuBridge.openShizuku(context); Unit }
        ShizukuState.OUTDATED -> "Shizuku 업데이트하기" to { ShizukuBridge.openInstallPage(context) }
        ShizukuState.NO_PERMISSION -> "권한 허용" to { vm.requestShizukuPermission(); Unit }
        ShizukuState.DENIED -> "Shizuku 에서 허용하기" to { ShizukuBridge.openShizuku(context); Unit }
        ShizukuState.READY -> return
    }
    val installed = s != ShizukuState.NOT_INSTALLED
    val running = installed && s != ShizukuState.NOT_RUNNING && s != ShizukuState.OUTDATED

    SectionCard(Icons.Rounded.Security, "처음 한 번 준비") {
        Column {
            Text(
                "안드로이드 13부터 일반 앱은 다른 앱의 Android/data 폴더에 쓸 수 없습니다. " +
                    "Shizuku 가 가진 adb(shell) 권한으로 게임 폴더의 한패 파일 하나만 바꿉니다. root 는 필요 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SetupStep(1, "Shizuku 설치", done = installed, current = !installed)
            SetupStep(
                2, "Shizuku 실행",
                "Shizuku 앱 → 무선 디버깅으로 시작. Wi-Fi 에 연결돼 있어야 합니다 (폰 핫스팟만 켠 상태에서는 안 됨). " +
                    "PC 가 있으면 USB 로 연결해서 Shizuku 앱에 나오는 adb 명령으로도 시작할 수 있습니다.",
                done = running, current = installed && !running,
            )
            SetupStep(3, "이 앱에 권한 허용", done = false, current = running)
            when (s) {
                ShizukuState.NOT_RUNNING -> Notice("무선 디버깅·adb 로 시작한 Shizuku 는 폰을 다시 켜면 꺼집니다. 재부팅 뒤에는 Shizuku 앱에서 다시 시작하세요.")
                ShizukuState.DENIED -> Notice(
                    "권한 요청을 '다시 묻지 않음'으로 거부해서 요청 창이 더 뜨지 않습니다. " +
                        "Shizuku 앱의 앱 관리에서 '소전2 한글패치'를 허용하세요."
                )
                ShizukuState.OUTDATED -> Notice("설치된 Shizuku 가 너무 오래됐습니다 (v11 미만). 최신 버전으로 업데이트하세요.")
                else -> {}
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = action,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(actionLabel, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, sub: String? = null, done: Boolean, current: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = when {
                done -> cs.primary
                current -> cs.primaryContainer
                else -> cs.surfaceVariant
            },
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Rounded.Check, contentDescription = "완료", tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                else Text(
                    n.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (current) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (done || current) cs.onSurface else cs.onSurfaceVariant,
            )
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PatchFileCard(ui: UiState, vm: PatchViewModel) {
    SectionCard(Icons.Rounded.Folder, "한패 파일") {
        Column {
            InfoRow("캐시", if (ui.cacheSize >= 0) PatchViewModel.human(ui.cacheSize) else "없음")
            InfoRow("SHA-256", if (ui.cacheSha.isEmpty()) "-" else ui.cacheSha.take(16) + "…", mono = true)
            InfoRow("마지막 확인", PatchViewModel.time(ui.checkedAt))
            InfoRow("마지막 적용", PatchViewModel.time(ui.appliedAt))
            InfoRow(
                "게임 버전",
                when {
                    ui.patchMisaligned -> "받은 한패는 이 버전용이지만 문장 자리가 원문과 맞지 않습니다"
                    ui.patchMatchesGame == true -> "받은 한패가 지금 게임 버전용입니다"
                    ui.patchMatchesGame == false -> "받은 한패는 이전 게임 버전용입니다"
                    else -> "확인 전"
                }
            )
            InfoRow(
                "번역 엔진",
                ui.engineName + (ui.engineFallback?.let { " · 네이티브를 못 올림: $it" } ?: "")
            )
            if (ui.status == PatchStatus.REPAIRED) {
                InfoRow(
                    "임시 복구",
                    (if (ui.repairedCoverage >= 0) "한국어 " + PatchViewModel.percent(ui.repairedCoverage.toDouble()) + " · " else "") +
                        PatchViewModel.time(ui.repairedAt)
                )
            }
            InfoRow(
                "번역 메모리",
                if (ui.memorySize > 0) "%,d줄 · %s".format(ui.memorySize, PatchViewModel.time(ui.memoryAt)) else "없음"
            )
            if (ui.canBootstrap) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = { vm.prepareMemory() }, enabled = !ui.busy) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("번역 메모리 준비")
                }
                Text(
                    "처음 적용할 때 백업해 둔 원본과 같은 버전의 옛 한패(약 50MB)를 GitHub 이력에서 받아 만듭니다. " +
                        "게임 업데이트 뒤 새 한패가 나오기 전에 임시 복구할 때 씁니다. " +
                        "Wi-Fi 에서는 앱을 열면 자동으로 준비합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                vm.patchUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "ETag → 304 → 해시 순으로 비교해서, 서버 파일이 그대로면 다시 받지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 백그라운드 확인·알림 설정. 기본은 전부 꺼져 있고(예약 작업 없음), 켤 때 알림 권한을 묻는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(ui: UiState, vm: PatchViewModel) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onNotificationPermission()
    }
    val needsPermission = Build.VERSION.SDK_INT >= 33 && !ui.notificationsAllowed
    SectionCard(Icons.Rounded.Notifications, "백그라운드 확인·알림") {
        Column {
            SwitchRow(
                "백그라운드에서 확인",
                if (ui.autoCheck) "${ui.checkIntervalHours}시간마다 한 번, 네트워크가 있고 배터리·저장공간이 부족하지 않을 때만 잠깐 확인합니다. " +
                    "바뀐 게 없으면 파일을 다시 읽지 않고, 게임 파일은 바꾸지 않습니다."
                else "꺼져 있으면 앱을 열 때만 확인합니다 (백그라운드 작업 없음).",
                ui.autoCheck, enabled = true,
            ) { on ->
                vm.setAutoCheck(on)
                // 알림 권한은 기능을 켜는 이 순간에만 묻는다
                if (on && needsPermission) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ui.autoCheck) {
                Text(
                    "확인 주기",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
                val choices = listOf(6, 12, 24)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { i, h ->
                        SegmentedButton(
                            selected = ui.checkIntervalHours == h,
                            onClick = { vm.setCheckInterval(h) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = choices.size),
                        ) { Text("${h}시간") }
                    }
                }
                SwitchRow(
                    "모바일 데이터로도 받기",
                    "끄면 백그라운드에서는 Wi-Fi 일 때만 한패(약 56MB)를 받고, 모바일 데이터에서는 새 한패가 있는지만 봅니다. " +
                        "앱에서 직접 누른 적용·확인은 항상 받습니다.",
                    ui.allowMobileData, enabled = true,
                ) { vm.setAllowMobileData(it) }

                Text(
                    "알림",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SwitchRow("한글패치가 풀렸을 때", "게임 업데이트 등으로 한패가 원본으로 바뀌었을 때", ui.notifyUnpatched, enabled = true) {
                    vm.setNotifyUnpatched(it)
                }
                SwitchRow("정식 한글패치가 나왔을 때", "임시 복구 중에 게임 버전에 맞는 한패가 올라왔을 때", ui.notifyOfficial, enabled = true) {
                    vm.setNotifyOfficial(it)
                }
                SwitchRow("번역 갱신", "같은 게임 버전 한패의 번역이 고쳐졌을 때. 일주일에 여러 번 올 수 있습니다", ui.notifyUpdate, enabled = true) {
                    vm.setNotifyUpdate(it)
                }
                if (needsPermission && (ui.notifyUnpatched || ui.notifyOfficial || ui.notifyUpdate)) {
                    Notice("알림 권한이 없어서 알려 드릴 수 없습니다. 확인 결과는 앱을 열면 보입니다.")
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("알림 허용")
                    }
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("안드로이드 알림 설정 열기") }
                if (ui.lastCheckAt > 0) {
                    Text(
                        "마지막 확인 " + PatchViewModel.time(ui.lastCheckAt) + " · " + ui.lastCheckNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun MaintenanceCard(ui: UiState, vm: PatchViewModel) {
    SectionCard(Icons.Rounded.CleaningServices, "관리") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.restoreBackup() },
                    enabled = !ui.busy && ui.hasBackup,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("원본 복원")
                }
                OutlinedButton(
                    onClick = { vm.removePatch() },
                    enabled = !ui.busy && ui.gamePkg != null &&
                        ui.status != PatchStatus.NOT_PATCHED && ui.status != PatchStatus.OFFICIAL,
                    modifier = Modifier.weight(1f),
                ) { Text("패치 제거") }
            }
            Text(
                if (ui.hasBackup) "최초 적용 시 원본 파일을 백업해 두었습니다."
                else "게임 폴더에 파일이 있던 경우, 최초 적용 시 자동 백업됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 지우기 전에 물어보는 항목: 지우면 되돌릴 수 없는 기능이 생긴다 */
private enum class StorageItem(val title: String, val warning: String) {
    MEMORY(
        "번역 메모리를 지울까요?",
        "게임 업데이트 뒤 새 한패가 나오기 전의 임시 복구를 못 하게 됩니다. " +
            "게임 원본과 같은 버전 한패를 다시 적용하면 새로 만들어집니다.",
    ),
    OFFICIAL(
        "공식 원본 보관본을 지울까요?",
        "지금 게임 버전으로 임시 복구하거나 번역 메모리를 새 번역으로 갱신할 때 씁니다. " +
            "게임 파일이 원본(중국어)으로 돌아오면 다시 보관합니다.",
    ),
    BACKUP(
        "원본 백업을 지울까요?",
        "'원본 복원'을 못 하게 되고, 번역 메모리를 준비하는 데도 못 씁니다.",
    ),
}

@Composable
private fun StorageCard(ui: UiState, vm: PatchViewModel) {
    var confirm by remember { mutableStateOf<StorageItem?>(null) }
    val s = ui.storage
    SectionCard(
        Icons.Rounded.Storage,
        "저장 공간",
        trailing = { Text(PatchViewModel.human(s.total), style = MaterialTheme.typography.bodyMedium) },
    ) {
        Column {
            StorageRow("캐시", "받은 한패·임시 복구본. 필요하면 다시 받거나 만듭니다", s.cache, "캐시 삭제", !ui.busy) {
                vm.clearCache()
            }
            StorageRow(
                "번역 메모리",
                (if (ui.memorySize > 0) "%,d줄 · ".format(ui.memorySize) else "") +
                    "최대 500MB, 넘으면 오래된 줄부터 뺍니다",
                s.memory, "삭제", !ui.busy,
            ) { confirm = StorageItem.MEMORY }
            StorageRow("공식 원본 보관본", "임시 복구와 번역 메모리 갱신에 씁니다", s.official, "삭제", !ui.busy) {
                confirm = StorageItem.OFFICIAL
            }
            StorageRow("원본 백업", "원본 복원에 씁니다", s.backup, "삭제", !ui.busy) { confirm = StorageItem.BACKUP }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(item.title) },
            text = { Text(item.warning) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (item) {
                        StorageItem.MEMORY -> vm.deleteMemory()
                        StorageItem.OFFICIAL -> vm.deleteOfficial()
                        StorageItem.BACKUP -> vm.deleteBackups()
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun StorageRow(title: String, desc: String, bytes: Long, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title + "  " + (if (bytes > 0) PatchViewModel.human(bytes) else "없음"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick, enabled = enabled && bytes > 0) { Text(action) }
    }
}

@Composable
private fun LogCard(ui: UiState) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        Icons.Rounded.Bolt,
        "로그",
        trailing = {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            }
        },
    ) {
        Column {
            val lines = if (expanded) ui.log.takeLast(60) else ui.log.takeLast(3)
            if (lines.isEmpty()) {
                Text("아직 기록이 없습니다", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            } else {
                Box(Modifier.height(if (expanded) 260.dp else 68.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        lines.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(color = Color.Transparent, thickness = 2.dp)
                        }
                    }
                }
            }
        }
    }
}
