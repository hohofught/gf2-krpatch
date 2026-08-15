package com.hoho.snqxkr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
        setContent {
            SnqxKRTheme {
                val model: PatchViewModel = viewModel()
                vm = model
                PatchScreen(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm?.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
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
            item { StatusHero(ui) }
            item { ActionButtons(ui, vm) }
            item { GameCard(ui) }
            item { ShizukuCard(ui, vm) }
            item { PatchFileCard(ui, vm) }
            item { MaintenanceCard(ui, vm) }
            item { LogCard(ui) }
            item {
                Text(
                    "패치 원본: nemasdf/haguel-baefo · 게임을 완전히 종료한 상태에서 적용하세요",
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
            "소녀전선2: 망명 중섭 클라이언트를 찾지 못했습니다", cs.errorContainer, cs.onErrorContainer
        )
        ui.shizuku != ShizukuState.READY -> HeroLook(
            Icons.Rounded.Security, "Shizuku 준비 필요",
            "게임 폴더에 쓰려면 Shizuku 연결이 필요합니다", cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.PATCHED_LATEST -> HeroLook(
            Icons.Rounded.CheckCircle, "최신 한글패치 적용됨",
            "적용 " + PatchViewModel.time(ui.appliedAt) + " · 확인 " + PatchViewModel.time(ui.checkedAt),
            cs.primaryContainer, cs.onPrimaryContainer
        )
        ui.status == PatchStatus.PATCHED_OLD -> HeroLook(
            Icons.Rounded.Update, "업데이트 있음",
            "새 한패가 올라왔습니다. 적용을 눌러 갱신하세요", cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.FOREIGN -> HeroLook(
            Icons.Rounded.Warning, "다른 파일 감지",
            "이 앱이 넣지 않은 한패가 들어 있습니다 (" + PatchViewModel.human(ui.targetSize) + ")",
            cs.tertiaryContainer, cs.onTertiaryContainer
        )
        ui.status == PatchStatus.NOT_PATCHED -> HeroLook(
            Icons.Rounded.Translate, "한글패치 미적용",
            "아래 버튼 한 번이면 다운로드부터 적용까지 끝납니다", cs.surfaceVariant, cs.onSurfaceVariant
        )
        else -> HeroLook(
            Icons.Rounded.HelpOutline, "상태 확인 필요",
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.downloadAndApply() },
            enabled = !ui.busy && ui.gamePkg != null && ui.shizuku == ShizukuState.READY,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("한글패치 적용", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
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
                InfoRow("대상 경로", Paths.tableDir(ui.gamePkg), mono = true)
                if (ui.targetSize >= 0) {
                    InfoRow("현재 파일", PatchViewModel.human(ui.targetSize) + " · " + PatchViewModel.time(ui.targetTime))
                    InfoRow("SHA-256", ui.targetSha.take(16) + "…", mono = true)
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(ui: UiState, vm: PatchViewModel) {
    val context = LocalContext.current
    val (label, desc) = when (ui.shizuku) {
        ShizukuState.READY -> "연결됨" to
                ("권한 있음" + if (ui.privilegedUid >= 0) " · uid " + ui.privilegedUid +
                        (if (ui.privilegedUid == 0) " (root)" else if (ui.privilegedUid == 2000) " (shell)" else "") else "")
        ShizukuState.NO_PERMISSION -> "권한 필요" to "아래 버튼을 눌러 이 앱에 Shizuku 권한을 허용하세요"
        ShizukuState.NOT_RUNNING -> "미실행" to "Shizuku 앱을 열어 서비스를 시작하세요 (무선 디버깅 또는 root)"
        ShizukuState.NOT_INSTALLED -> "미설치" to "Shizuku 앱이 필요합니다"
    }
    SectionCard(Icons.Rounded.Security, "Shizuku") {
        Column {
            InfoRow("상태", label)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when (ui.shizuku) {
                ShizukuState.NO_PERMISSION -> FilledTonalButton(onClick = { vm.requestShizukuPermission() }) {
                    Text("권한 요청")
                }
                ShizukuState.NOT_RUNNING, ShizukuState.NOT_INSTALLED -> FilledTonalButton(onClick = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent != null) context.startActivity(intent)
                }) { Text("Shizuku 열기") }
                ShizukuState.READY -> {}
            }
        }
    }
}

@Composable
private fun PatchFileCard(ui: UiState, vm: PatchViewModel) {
    SectionCard(Icons.Rounded.Folder, "한패 파일") {
        Column {
            InfoRow("캐시", if (ui.cacheSize >= 0) PatchViewModel.human(ui.cacheSize) else "없음")
            InfoRow("SHA-256", if (ui.cacheSha.isEmpty()) "-" else ui.cacheSha.take(16) + "…", mono = true)
            InfoRow("마지막 확인", PatchViewModel.time(ui.checkedAt))
            InfoRow("마지막 적용", PatchViewModel.time(ui.appliedAt))
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
                    enabled = !ui.busy && ui.status != PatchStatus.NOT_PATCHED && ui.gamePkg != null,
                    modifier = Modifier.weight(1f),
                ) { Text("패치 제거") }
            }
            TextButton(onClick = { vm.clearCache() }, enabled = !ui.busy) {
                Text("다운로드 캐시 비우기")
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
