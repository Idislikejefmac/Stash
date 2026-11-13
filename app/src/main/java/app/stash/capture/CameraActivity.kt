package app.stash.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.stash.data.CaptureSource
import app.stash.domain.CameraGate
import app.stash.domain.CameraRules
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.share.AnnotateActions
import app.stash.share.AnnotateScreen
import app.stash.share.ShareDepsProvider
import app.stash.share.ShareViewModel
import app.stash.share.annotateUiFor
import app.stash.ui.ScreenSecurity

/**
 * The camera inside Stash (docs/cards/16e). An adding activity: it shows the live camera and the picture just taken,
 * never anything stored. The picture is taken in memory and goes straight into private storage; it never reaches the
 * gallery. FLAG_SECURE is set in onCreate (D80).
 *
 * Opened with no extra, it makes a new note: each picture joins the "Anything to add about this?" screen, whose
 * Camera button comes back here. Opened with the extra [EXTRA_CAPTURE_ID], it adds pictures to that note and
 * never reads or shows the note.
 */
class CameraActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CAPTURE_ID = "capture_id"

        /** [captureId] null: a new note. Otherwise the id of the note to add to (an id and nothing else). */
        fun intent(context: Context, captureId: String? = null): Intent =
            Intent(context, CameraActivity::class.java).also { if (captureId != null) it.putExtra(EXTRA_CAPTURE_ID, captureId) }
    }

    private val captureId: String? by lazy { intent.getStringExtra(EXTRA_CAPTURE_ID) }
    private lateinit var deps: CameraDeps

    private val vm: CameraViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CameraViewModel(CameraDepsProvider.get(application), captureId) as T
        }
    }

    /** The annotate step of a new note. Created only when a new note needs it. */
    private val share: ShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShareViewModel(ShareDepsProvider.get(application, CaptureSource.PHOTO)) as T
        }
    }

    private var locked by mutableStateOf(false)
    private var gate by mutableStateOf(CameraGate.OPEN)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshGate() }

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            if (captureId == null) {
                share.addPicked(uris)
                vm.screen = CameraViewModel.Screen.ANNOTATE
            } else {
                vm.addPicked(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshot of this screen and a blank card in the recent-apps view (D80). Unconditional, every build.
        ScreenSecurity.secure(window)
        // Opens over the lock screen like CaptureActivity (the manifest also says so); whether the camera works there is a phone check.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        deps = CameraDepsProvider.get(application)
        refreshGate()
        if (savedInstanceState == null) Dbg.event(DbgComponent.CAMERA, DbgCode.OPENED, n1 = if (locked) 1 else 0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (vm.screen) {
                        CameraViewModel.Screen.ANNOTATE -> AnnotateScreen(
                            annotateUiFor(share, cameraVisible = CameraRules.buttonVisible(deps.cameraSwitchOn(), deps.picturesOn(), deps.hasCamera())),
                            annotateActions,
                        )
                        CameraViewModel.Screen.ADDED -> AddedScreen()
                        CameraViewModel.Screen.CAMERA -> if (gate == CameraGate.OPEN) CameraScreen() else BlockedScreen()
                    }
                    app.stash.ui.ScreenName(when (vm.screen) {
                        CameraViewModel.Screen.ANNOTATE -> "Picture: note"
                        CameraViewModel.Screen.ADDED -> "Picture: added"
                        CameraViewModel.Screen.CAMERA -> "Camera"
                    })
                    if (captureId == null) LaunchedEffect(share.finishRequested) { if (share.finishRequested) finishAndRemoveTask() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGate()
        // Ask once, when the camera is first opened with the phone unlocked.
        if (gate == CameraGate.ASK_PERMISSION && !vm.askedPermission && vm.screen == CameraViewModel.Screen.CAMERA) {
            vm.askedPermission = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun refreshGate() {
        locked = deps.isLocked()
        gate = CameraRules.gate(deps.cameraSwitchOn(), deps.picturesOn(), deps.hasCamera(), deps.permissionGranted(), locked)
        if (captureId == null) share.locked = locked
    }

    /** Back, and the Close button: from the camera of a new note with pictures already taken it returns to them; otherwise it leaves. */
    private fun back() {
        when (vm.screen) {
            CameraViewModel.Screen.ANNOTATE -> if (share.enlarged != null) share.closeEnlarged() else share.cancel()
            CameraViewModel.Screen.CAMERA ->
                if (captureId == null && (share.pictures.isNotEmpty() || share.pending > 0)) vm.screen = CameraViewModel.Screen.ANNOTATE
                else finishAndRemoveTask()
            CameraViewModel.Screen.ADDED -> finishAndRemoveTask()
        }
    }

    private val annotateActions = object : AnnotateActions {
        override fun onTextChange(text: String) = share.onTextChange(text)
        override fun onFieldFocused() = share.stopListening()
        override fun onMic() = share.toggleMic()
        override fun onGallery() {
            share.stopListening()
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        override fun onCamera() {
            share.stopListening()
            vm.screen = CameraViewModel.Screen.CAMERA
        }
        override fun onPrivate() = share.setPrivate(!share.markPrivate)
        override fun onSave() = share.onSave()
        override fun onCancel() = share.cancel()
        override fun onRemove(id: String) { share.pictures.firstOrNull { it.attachment.id == id }?.let(share::remove) }
        override fun onOpen(id: String) { share.pictures.firstOrNull { it.attachment.id == id }?.let(share::open) }
        override fun onCloseEnlarged() = share.closeEnlarged()
    }

    // ---------------------------------------------------------------- screens

    /** Full-screen preview, one large shutter button, Close, and a flash button (Off and Auto). Nothing else. */
    @androidx.compose.runtime.Composable
    private fun CameraScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera")) {
            vm.engine(applicationContext).Preview(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                OutlinedButton(
                    onClick = { back() },
                    modifier = Modifier.align(Alignment.TopStart).heightIn(min = 56.dp).testTag("close"),
                ) { Text(CameraRules.CLOSE) }
                OutlinedButton(
                    onClick = { vm.toggleFlash() },
                    modifier = Modifier.align(Alignment.TopEnd).heightIn(min = 56.dp).testTag("flash"),
                ) { Text(CameraRules.flashLabel(vm.flash)) }
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    vm.message?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp).testTag("message")) }
                    if (vm.busy) Text("Saving...", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp).testTag("busy"))
                    Box(
                        Modifier.size(80.dp).alpha(if (vm.busy) 0.4f else 1f).clip(CircleShape).background(Color.White)
                            .border(4.dp, Color.Gray, CircleShape)
                            .clickable(enabled = !vm.busy, role = Role.Button) { shoot() }
                            .semantics { contentDescription = CameraRules.SHUTTER }
                            .testTag("shutter"),
                    )
                }
            }
        }
    }

    private fun shoot() = vm.shoot(applicationContext) { prepared -> share.addPrepared(prepared) }

    /** The camera cannot be used (permission, switch, no camera): say so, and offer the gallery picker. */
    @androidx.compose.runtime.Composable
    private fun BlockedScreen() {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val text = CameraRules.message(gate) ?: CameraRules.NEEDS_PERMISSION
            Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("notice"))
            vm.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp).testTag("message")) }
            Spacer(Modifier.height(24.dp))
            if (gate == CameraGate.ASK_PERMISSION) {
                Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 56.dp).testTag("allow")) { Text(CameraRules.ALLOW_THE_CAMERA) }
                Spacer(Modifier.height(12.dp))
            }
            if (!locked && deps.picturesOn()) {
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("gallery"),
                ) { Text(CameraRules.CHOOSE_FROM_GALLERY) }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(onClick = { back() }, modifier = Modifier.heightIn(min = 56.dp).testTag("close")) { Text(CameraRules.CLOSE) }
        }
    }

    /** An existing note, after a picture: what was added, then "Take another" or "Done". */
    @androidx.compose.runtime.Composable
    private fun AddedScreen() {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(CameraRules.added(vm.added), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("added"))
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.takeAnother() }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("another")) {
                Text(CameraRules.TAKE_ANOTHER, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("done")) { Text(CameraRules.DONE) }
        }
    }
}
