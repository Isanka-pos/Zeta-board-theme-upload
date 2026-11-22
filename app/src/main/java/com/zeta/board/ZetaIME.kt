package com.zeta.board

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.File

class ZetaIME : InputMethodService() {

    private var host: KeyboardHost? = null
    private var video: VideoBgView? = null
    private var secure = false
    private var expanding = false
    private var cm: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(true) }

    private val output = object : KeyboardHost.Output {
        override fun commit(s: String) {
            currentInputConnection?.commitText(s, 1)
        }

        override fun del() {
            val ic = currentInputConnection ?: return
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) {
                ic.commitText("", 1)
                return
            }
            // delete a whole emoji (flag, family, skin tone, heart + selector...) in one tap
            val before = ic.getTextBeforeCursor(32, 0)
            val n = if (before.isNullOrEmpty()) 0 else Emojis.lastClusterLength(before)
            if (n > 0) ic.deleteSurroundingText(n, 0) else ic.deleteSurroundingTextInCodePoints(1, 0)
        }

        override fun enter() {
            val ic = currentInputConnection ?: return
            val ei = currentInputEditorInfo
            val opts = ei?.imeOptions ?: 0
            val action = opts and EditorInfo.IME_MASK_ACTION
            val noAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (ei == null || noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else {
                ic.performEditorAction(action)
            }
        }

        override fun cursor(dir: Int) {
            sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        override fun settings() {
            startActivity(Intent(this@ZetaIME, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun sendSticker(f: File): Boolean {
            val ic = currentInputConnection ?: return false
            val ei = currentInputEditorInfo ?: return false
            return try {
                StickerSender.send(this@ZetaIME, ic, ei, f)
            } catch (e: Exception) {
                Crash.log("sendSticker", e)
                false
            }
        }

        override fun shareSticker(f: File) {
            try {
                val uri: Uri = StickerSender.uriFor(this@ZetaIME, f)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/webp")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(send, "Send sticker")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(chooser)
            } catch (e: Exception) {
            }
        }

        override fun expand(delim: String): Boolean {
            if (secure || expanding) return false
            if (!Prefs.getB(this@ZetaIME, "quick_auto", true)) return false
            if (!QuickRepo.hasEnabled()) return false
            val ic = currentInputConnection ?: return false
            expanding = true
            try {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return false
                val tok = QuickText.tokenAtEnd(before) ?: return false
                val msg = QuickRepo.lookup(tok) ?: return false
                ic.beginBatchEdit()
                try {
                    if (!ic.deleteSurroundingText(tok.length, 0)) return false
                    ic.commitText(msg, 1)
                    ic.commitText(delim, 1)
                } finally {
                    ic.endBatchEdit()
                }
                return true
            } catch (e: Exception) {
                Crash.log("expand", e)
                return false
            } finally {
                expanding = false
            }
        }

        override fun quickSuggestion(): String? {
            if (secure || !Prefs.getB(this@ZetaIME, "quick_suggest", true)) return null
            if (!QuickRepo.hasEnabled()) return null
            val ic = currentInputConnection ?: return null
            return try {
                // the selection is already known from onUpdateSelection: no extra round trip to the app while nothing is selected
                if (selStart != selEnd && !ic.getSelectedText(0).isNullOrEmpty()) return null
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return null
                val tok = QuickText.tokenAtEnd(before) ?: return null
                QuickRepo.lookup(tok)
            } catch (e: Exception) {
                null
            }
        }

        override fun applyQuick(): Boolean {
            if (secure || expanding) return false
            val ic = currentInputConnection ?: return false
            expanding = true
            try {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return false
                val tok = QuickText.tokenAtEnd(before) ?: return false
                val msg = QuickRepo.lookup(tok) ?: return false
                ic.beginBatchEdit()
                try {
                    if (!ic.deleteSurroundingText(tok.length, 0)) return false
                    ic.commitText(msg, 1)
                } finally {
                    ic.endBatchEdit()
                }
                return true
            } catch (e: Exception) {
                return false
            } finally {
                expanding = false
            }
        }

        override fun insertQuick(text: String): Boolean {
            if (secure) return false
            val ic = currentInputConnection ?: return false
            return try {
                ic.commitText(text, 1)
            } catch (e: Exception) {
                false
            }
        }

        override fun quickAllowed(): Boolean = !secure

        override fun voiceAllowed(): Boolean = !secure

        override fun charBeforeCursor(): Char? = try {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.firstOrNull()
        } catch (e: Exception) {
            null
        }

        override fun requestMic() {
            try {
                startActivity(Intent(this@ZetaIME, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        override fun setComposing(text: String) {
            try {
                currentInputConnection?.setComposingText(text, 1)
            } catch (e: Exception) {
            }
        }

        override fun finishComposing() {
            try {
                currentInputConnection?.finishComposingText()
            } catch (e: Exception) {
            }
        }

        override fun replaceBefore(n: Int, text: String): Boolean {
            val ic = currentInputConnection ?: return false
            if (selStart != selEnd) return false
            return try {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(n, 0)
                ic.commitText(text, 1)
                ic.endBatchEdit()
                true
            } catch (e: Exception) {
                false
            }
        }

        override fun textBefore(n: Int): CharSequence? = try {
            currentInputConnection?.getTextBeforeCursor(n, 0)
        } catch (e: Exception) {
            null
        }

        override fun translateAllowed(): Boolean = !secure

        override fun translateSource(): TrSource? {
            val ic = currentInputConnection ?: return null
            return try {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrEmpty()) {
                    if (sel.isBlank()) TrSource("", true, "Select some text first") else TrSource(sel, true)
                } else if (selStart != selEnd) {
                    TrSource("", true, "This app does not let the keyboard read the selection")
                } else {
                    val before = ic.getTextBeforeCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: ""
                    val after = ic.getTextAfterCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: ""
                    val all = before + after
                    if (all.isBlank()) TrSource("", false, "Type a message first") else TrSource(all, false)
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun applyTranslation(src: TrSource, translated: String): Boolean {
            if (secure || translated.isEmpty()) return false
            val ic = currentInputConnection ?: return false
            return try {
                if (src.selection) {
                    if (ic.getSelectedText(0)?.toString() != src.text) return false
                    ic.commitText(translated, 1)
                } else {
                    val before = ic.getTextBeforeCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: return false
                    val after = ic.getTextAfterCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: return false
                    if (before + after != src.text) return false
                    ic.beginBatchEdit()
                    try {
                        if (!ic.deleteSurroundingText(before.length, after.length)) return false
                        ic.commitText(translated, 1)
                    } finally {
                        ic.endBatchEdit()
                    }
                }
            } catch (e: Exception) {
                Crash.log("applyTranslation", e)
                false
            }
        }

        override fun openQuickManager() {
            try {
                startActivity(Intent(this@ZetaIME, QuickTextActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        override fun openStatusSetup(kind: String) {
            try {
                startActivity(
                    Intent(this@ZetaIME, StatusSetupActivity::class.java).putExtra("kind", kind)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (e: Exception) {
            }
        }

        override fun requestStoragePermission() = openStatusSetup("perm")

        override fun sendMedia(f: File, mime: String): Boolean {
            val ic = currentInputConnection ?: return false
            val ei = currentInputEditorInfo ?: return false
            return try {
                StickerSender.sendMedia(this@ZetaIME, ic, ei, f, mime)
            } catch (e: Exception) {
                Crash.log("sendMedia", e)
                false
            }
        }

        override fun shareMedia(files: List<File>, mimes: List<String>) {
            try {
                if (files.isEmpty()) return
                val uris = ArrayList<Uri>()
                for (f in files) uris.add(StickerSender.uriFor(this@ZetaIME, f))
                val majors = mimes.map { it.substringBefore('/') }.toSet()
                val type = if (uris.size == 1) mimes[0] else if (majors.size == 1) majors.first() + "/*" else "*/*"
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(
                    Intent.createChooser(send, "Send status")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
            }
        }

        override fun pickPhoto() {
            startActivity(
                Intent(this@ZetaIME, StickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        ClipRepo.init(this)
        QuickRepo.init(this)
        val m = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm = m
        m.addPrimaryClipChangedListener(clipListener)
        UpdateScheduler.schedule(this)
        UpdateWatcher.start(this)
    }

    override fun onDestroy() {
        KeyClick.release()
        cm?.removePrimaryClipChangedListener(clipListener)
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeStatus()
        host?.closeQuick()
        host?.closeMix()
        host?.closeTools()
        host?.cancelWork()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Where the cursor / selection is in the focused field (as the app last reported it). */
    private var selStart = 0
    private var selEnd = 0

    /** The text or cursor changed (typing, the app clearing the field after Send, the user moving the cursor): update the Quick Text suggestion. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        host?.onSelectionChanged()
        host?.scheduleSuggestion()
    }

    override fun onCreateInputView(): View {
        val tr = Perf.begin("keyboard_view_create")
        try {
            return buildInputView()
        } finally {
            Perf.end(tr)
        }
    }

    private fun buildInputView(): View {
        val h = KeyboardHost(this, output)
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.onSecure = { on -> ScreenGuard.setSecure(window?.window, on && ScreenGuard.blocking(this)) }
        host = h
        // video background: a player view sits behind the keyboard; the frame shows the theme colours until the first video frame
        val v = VideoBgView(this)
        video = v
        val frame = FrameLayout(this)
        fun paintFrame(t: KbTheme) {
            frame.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        }
        paintFrame(h.kv.theme)
        val prev = h.kv.onTheme
        h.kv.onTheme = {
            prev?.invoke(it)
            paintFrame(it)
        }
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return frame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        val tr = Perf.begin("keyboard_start_input")
        try {
            startInput(info, restarting)
        } finally {
            Perf.end(tr)
        }
    }

    private fun startInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val h = host ?: return
        val t = info?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val v = t and InputType.TYPE_MASK_VARIATION
        secure = (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        h.resetSinglish()
        h.closeClipboard()
        h.closeStickers()
        h.closeStatus()
        h.closeQuick()
        h.closeMix()
        if (!restarting) h.cancelWork()
        h.closeTools()
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.kv.reload()
        video?.sync()
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        val cap = cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        h.kv.startInput(layer, if (cap) 1 else 0)
        if (!secure) h.onStartInput()
        selStart = info?.initialSelStart ?: 0
        selEnd = info?.initialSelEnd ?: 0
        h.refreshSuggestion(true)
        captureClip(false)
        UpdateWatcher.checkSoon(this)
        RemoteCfg.refresh {}
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Perf.flushDraw()
        host?.flushSinglish()
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeStatus()
        host?.closeQuick()
        host?.closeMix()
        host?.closeTools()
        host?.cancelWork()
        video?.pause()
        super.onFinishInputView(finishingInput)
    }

    /**
     * Stores the current clip locally. Skips sensitive clips, password fields and incognito fields.
     * [fromCopy] is true when the user just copied something; when the keyboard only opens, a clip that was
     * already handled (for example one the user deleted from history) is not added back.
     */
    private fun captureClip(fromCopy: Boolean) {
        if (secure || !Prefs.getB(this, "clip_on", true)) return
        try {
            val clip = cm?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (ClipGuard.looksSensitive(text)) return
            val h = text.hashCode()
            if (!fromCopy && Prefs.getI(this, "clip_hash", 0) == h) return
            Prefs.setI(this, "clip_hash", h)
            ClipRepo.add(text, Prefs.getI(this, "clip_max", 100))
        } catch (e: Exception) {
        }
    }
}
