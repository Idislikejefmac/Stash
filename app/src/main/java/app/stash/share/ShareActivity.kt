package app.stash.share

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.stash.domain.ShareRules

/**
 * Share to Stash (docs/cards/16c). An *adding* activity: it shows only what is being added now, never a stored note,
 * and it is not shown over the lock screen. Blocks screenshots and stays out of the recent-apps view (`FLAG_SECURE`,
 * `excludeFromRecents`). Posts no notification and sends nothing anywhere.
 */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShareViewModel(ShareDepsProvider.get(application)) as T
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> vm.addPicked(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshot of this screen, and a blank card in the recent-apps view.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Read what was shared right now: the permission to read a shared picture lasts only as long as this activity.
        // After a rotation the view model already has it, and nothing is read or prepared a second time.
        if (!vm.started) vm.begin(ShareIntentReader.read(intent))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    vm.enlarged != null -> vm.closeEnlarged()
                    vm.closeOnly -> finish()
                    else -> vm.cancel()
                }
            }
        })

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (vm.closeOnly) PicturesOffScreen(onClose = { finish() }) else AnnotateScreen(ui(), actions)
                    app.stash.ui.ScreenName("Share")
                    LaunchedEffect(vm.finishRequested) { if (vm.finishRequested) finish() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    private fun ui(): AnnotateUi = annotateUiFor(vm)

    private val actions = object : AnnotateActions {
        override fun onTextChange(text: String) = vm.onTextChange(text)
        override fun onFieldFocused() = vm.stopListening()
        override fun onMic() = vm.toggleMic()
        override fun onGallery() {
            vm.stopListening()
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        override fun onPrivate() = vm.setPrivate(!vm.markPrivate)
        override fun onSave() = vm.onSave()
        override fun onCancel() = vm.cancel()
        override fun onRemove(id: String) { vm.pictures.firstOrNull { it.attachment.id == id }?.let(vm::remove) }
        override fun onOpen(id: String) { vm.pictures.firstOrNull { it.attachment.id == id }?.let(vm::open) }
        override fun onCloseEnlarged() = vm.closeEnlarged()
    }
}
