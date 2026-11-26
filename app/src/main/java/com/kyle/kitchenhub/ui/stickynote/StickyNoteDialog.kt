package com.kyle.kitchenhub.ui.stickynote

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.StickyNoteStore
import com.kyle.kitchenhub.databinding.DialogStickyNoteBinding
import com.kyle.kitchenhub.service.audio.MicArbiter
import com.kyle.kitchenhub.service.audio.MicState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val NOTE_COLOR = "#FFF3B0"
private const val REMINDER_COLOR = "#D7E8FF"
private const val MODE_NOTE = 0
private const val MODE_REMINDER = 1
private const val MODE_AUTHOR = 2

class StickyNoteDialog(
    context: Context,
    private val editingNote: com.kyle.kitchenhub.data.StickyNote? = null,
    private val onSaved: () -> Unit
) : Dialog(context) {

    private var selectedColor = NOTE_COLOR
    private var selectedAuthor = "Note"
    private var authorMode = MODE_NOTE
    private var recognizer: SpeechRecognizer? = null
    private var wakeWordPausedForDictation = false
    private var preDictationPrefix = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var b: DialogStickyNoteBinding

    override fun show() = com.kyle.kitchenhub.core.ui.DialogImmersive.show(this) { super.show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = ConfigRepository.get(context)
        val bodyTf = if (cfg.stickyFont == "CAVEAT_CURSIVE") FontCache.caveat(context) else FontCache.patrickHand(context)
        val uiTf = FontCache.plusJakarta(context)
        val existing = editingNote
        if (existing != null) {
            selectedColor = existing.colorHex
            selectedAuthor = existing.author
        }

        b = DialogStickyNoteBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)

        b.tvDialogNoteHeader.typeface = uiTf
        b.tvDialogNoteHeader.paint.isFakeBoldText = true
        b.etDialogNoteInput.typeface = bodyTf
        b.etDialogNoteInput.setText(existing?.text ?: "")

        fun authorColor(name: String): String {
            val palette = listOf("#FFD9E2", "#D6F5D6", "#E8D9FF", "#FFE0B8", "#D0F0F0")
            return palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size]
        }

        fun applyMode(mode: Int) {
            authorMode = mode
            when (mode) {
                MODE_NOTE -> {
                    selectedAuthor = "Note"
                    selectedColor = NOTE_COLOR
                }
                MODE_REMINDER -> {
                    selectedAuthor = "Reminder"
                    selectedColor = REMINDER_COLOR
                }
                else -> {
                    val typed = b.etAuthorName.text?.toString()?.trim().orEmpty()
                    selectedAuthor = typed.ifEmpty { "Note" }
                    selectedColor = if (typed.isEmpty()) NOTE_COLOR else authorColor(typed)
                }
            }
            b.etAuthorName.visibility = if (mode == MODE_AUTHOR) View.VISIBLE else View.GONE
            b.tvDialogNoteHeader.text = "Sticky Notes • $selectedAuthor"
            runCatching { b.dialogNoteCard.setCardBackgroundColor(Color.parseColor(selectedColor)) }
        }

        if (existing != null) {
            when (existing.author) {
                "Note" -> authorMode = MODE_NOTE
                "Reminder" -> authorMode = MODE_REMINDER
                else -> {
                    authorMode = MODE_AUTHOR
                    b.etAuthorName.setText(existing.author)
                }
            }
        }
        applyMode(authorMode)
        if (existing != null && authorMode == MODE_AUTHOR) selectedColor = existing.colorHex

        b.btnTypeNote.setOnClickListener { TactileAudioEngine.playClick(); applyMode(MODE_NOTE) }
        b.btnTypeReminder.setOnClickListener { TactileAudioEngine.playClick(); applyMode(MODE_REMINDER) }
        b.btnTypeAuthor.setOnClickListener {
            TactileAudioEngine.playClick()
            applyMode(MODE_AUTHOR)
            b.etAuthorName.requestFocus()
        }
        b.etAuthorName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (authorMode == MODE_AUTHOR) applyMode(MODE_AUTHOR)
            }
        })

        b.btnDialogSpeak.setOnClickListener {
            TactileAudioEngine.playClick()
            startVoiceDictation()
        }
        if (editingNote != null) {
            b.btnDialogClear.text = "Delete"
            b.btnDialogPin.text = "Save"
        }
        b.btnDialogClear.setOnClickListener {
            TactileAudioEngine.playClick()
            if (editingNote != null) {
                StickyNoteStore.remove(context, editingNote.id)
            } else {
                StickyNoteStore.clear(context)
            }
            onSaved()
            dismiss()
        }
        b.btnDialogPin.setOnClickListener {
            val txt = b.etDialogNoteInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                if (editingNote != null) {
                    StickyNoteStore.update(context, editingNote.id, txt, selectedAuthor, selectedColor)
                } else {
                    StickyNoteStore.save(context, txt, selectedAuthor, selectedColor)
                }
                TactileAudioEngine.playNoteChime()
            }
            onSaved()
            dismiss()
        }

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.58f)
        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.70f).toInt(), -2)
    }

    private fun startVoiceDictation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Mic permission required", Toast.LENGTH_SHORT).show()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "No Speech Recognition service installed on device", Toast.LENGTH_SHORT).show()
            resumeWakeWordIfPaused()
            return
        }

        val existingText = b.etDialogNoteInput.text?.toString()?.trim().orEmpty()
        preDictationPrefix = if (existingText.isEmpty()) "" else "$existingText "
        b.btnDialogSpeak.text = "Starting..."

        scope.launch {
            destroyRecognizerOnMain()
            if (!wakeWordPausedForDictation) {
                wakeWordPausedForDictation = true
                MicArbiter.enterState(MicState.STICKY_NOTE_DICTATING)
                delay(180L)
            }
            if (!isShowing) {
                resumeWakeWordIfPaused()
                return@launch
            }
            mainHandler.post {
                if (!isShowing) {
                    resumeWakeWordIfPaused()
                    return@post
                }
                val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        b.btnDialogSpeak.text = "Listening..."
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        b.btnDialogSpeak.text = "Speak"
                    }
                    override fun onError(error: Int) {
                        b.btnDialogSpeak.text = "Speak"
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                            SpeechRecognizer.ERROR_AUDIO -> "Mic busy"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission required"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network error"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            else -> "Speech error ($error)"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        destroyRecognizerOnMain()
                        resumeWakeWordIfPaused()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            val combined = preDictationPrefix + text
                            b.etDialogNoteInput.setText(combined)
                            b.etDialogNoteInput.setSelection(combined.length)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            val combined = preDictationPrefix + text
                            b.etDialogNoteInput.setText(combined)
                            b.etDialogNoteInput.setSelection(combined.length)
                        }
                        b.btnDialogSpeak.text = "Speak"
                        destroyRecognizerOnMain()
                        resumeWakeWordIfPaused()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    if (Build.VERSION.SDK_INT >= 33) putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
                }
                sr.startListening(intent)
            }
        }
    }

    private fun destroyRecognizerOnMain() {
        val destroy = {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroy()
        } else {
            mainHandler.post(destroy)
        }
    }

    private fun resumeWakeWordIfPaused() {
        if (!wakeWordPausedForDictation) return
        wakeWordPausedForDictation = false
        CoroutineScope(Dispatchers.Main.immediate).launch {
            if (MicArbiter.state == MicState.STICKY_NOTE_DICTATING) {
                MicArbiter.exitStateToWakeWord()
            }
        }
    }

    override fun onStop() {
        destroyRecognizerOnMain()
        resumeWakeWordIfPaused()
        scope.cancel()
        super.onStop()
    }

    override fun dismiss() {
        destroyRecognizerOnMain()
        resumeWakeWordIfPaused()
        super.dismiss()
    }
}
