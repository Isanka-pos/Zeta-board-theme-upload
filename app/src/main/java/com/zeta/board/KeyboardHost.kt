package com.zeta.board

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import java.io.File
import android.widget.LinearLayout

/** Text picked up for translation. [problem] is set (and [text] empty) when it cannot be translated, with a message to show. */
class TrSource(val text: String, val selection: Boolean, val problem: String? = null)

/**
 * Container used by the IME and by the in-app preview: the normal keyboard plus the clipboard panel.
 * All text goes through [Output], so both places behave the same.
 */
class KeyboardHost(ctx: Context, private val out: Output) : LinearLayout(ctx), KeyboardView.Listener, ClipPanel.Callback, StickerPanel.Callback, QuickPanel.Callback, ToolsPanel.Callback, StatusPanel.Callback, EmojiMixPanel.Callback {

    interface Output {
        fun commit(s: String)
        fun del()
        fun enter()
        fun cursor(dir: Int)
        fun settings()

        /** Sends a sticker into the current app. Returns false when that app does not accept stickers. */
        fun sendSticker(f: File): Boolean = false

        /** Fallback: let the user share the sticker with another app. */
        fun shareSticker(f: File) {}

        /** Opens the photo picker used by the sticker maker. */
        fun pickPhoto() {}

        /** Quick Text: replaces a just-typed shortcut before the cursor with its message. True when it did. */
        fun expand(delim: String): Boolean = false

        /** Quick Text: inserts a saved message into the focused field. False when there is no field or it is private. */
        fun insertQuick(text: String): Boolean = false

        /** True when Quick Text may be used in the current field (false for password / private fields). */
        fun quickAllowed(): Boolean = true

        /** Quick Text suggestion: the saved message when the word before the cursor is an enabled shortcut, else null. */
        fun quickSuggestion(): String? = null

        /** Quick Text suggestion: swaps the shortcut before the cursor for its message. True when it did. */
        fun applyQuick(): Boolean = false

        /** Opens the Quick Text Manager screen. */
        fun openQuickManager() {}

        /** Opens the screen where the user picks a WhatsApp status folder (kind: "wa", "w4b" or "any"). */
        fun openStatusSetup(kind: String) {}

        /** Allows saving into the gallery on Android 9 and older. */
        fun requestStoragePermission() {}

        /** Puts a photo/video into the chat box, when the app accepts that file type. */
        fun sendMedia(f: File, mime: String): Boolean = false

        fun shareMedia(files: List<File>, mimes: List<String>) {}

        /** Voice typing: false in password / private fields. */
        fun voiceAllowed(): Boolean = true

        /** Voice typing: the character just before the cursor, or null at the start of the field. */
        fun charBeforeCursor(): Char? = null

        /** Voice typing: opens the screen that asks for the microphone permission. */
        fun requestMic() {}

        /** Singlish typing: shows [text] in the field as underlined, still-changing text (an empty string removes it). */
        fun setComposing(text: String) {}

        /** Singlish typing: turns the underlined text into normal text. */
        fun finishComposing() {}

        /** Singlish typing: the [n] characters before the cursor, or null when there is no field. */
        fun textBefore(n: Int): CharSequence? = null

        /** Auto-fix: swaps the [n] characters before the cursor for [text]. False when that is not possible (no field, a selection). */
        fun replaceBefore(n: Int, text: String): Boolean = false

        /** Zeta Translate: false in password / private fields. */
        fun translateAllowed(): Boolean = true

        /** Zeta Translate: the text to translate (the selection, or the whole field when nothing is selected). Null when there is no field. */
        fun translateSource(): TrSource? = null

        /** Zeta Translate: swaps exactly [src] for [translated]. False when the field changed in the meantime. */
        fun applyTranslation(src: TrSource, translated: String): Boolean = false
    }

    val kv = KeyboardView(ctx)
    private val vm = ClipViewModel()
    private val panel = ClipPanel(ctx, vm, this)
    private val stickers = StickerPanel(ctx, this)
    private val quick = QuickPanel(ctx, this)
    private val tools = ToolsPanel(ctx, this)
    private val status = StatusPanel(ctx, this)
    private val emojiMix = EmojiMixPanel(ctx, this)
    private var panelOpen = false
    private var stickersOpen = false
    private var quickOpen = false
    private var toolsOpen = false
    private var statusOpen = false
    private var mixOpen = false
    private var typing = false
    /** After a suggestion was used the toolbar stays normal until the user types again (the message itself may end like a shortcut). */
    private var quickSuppress = false
    /** Tells the keyboard service when a private panel (clipboard / stickers) is open, so its window can block screenshots. */
    var onSecure: ((Boolean) -> Unit)? = null
    private fun syncSecure() { onSecure?.invoke(panelOpen || stickersOpen || quickOpen || statusOpen) }
    private val d = ctx.resources.displayMetrics.density

    init {
        orientation = VERTICAL
        kv.listener = this
        kv.onTheme = {
            panel.applyTheme(it)
            stickers.applyTheme(it)
            quick.applyTheme(it)
            tools.applyTheme(it)
            status.applyTheme(it)
            emojiMix.applyTheme(it)
        }
        panel.applyTheme(kv.theme)
        stickers.applyTheme(kv.theme)
        quick.applyTheme(kv.theme)
        tools.applyTheme(kv.theme)
        status.applyTheme(kv.theme)
        emojiMix.applyTheme(kv.theme)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(stickers, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stickers.visibility = GONE
        addView(quick, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        quick.visibility = GONE
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tools.visibility = GONE
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.visibility = GONE
        addView(emojiMix, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emojiMix.visibility = GONE
        addView(kv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.visibility = GONE
    }

    private fun applyHeights() {
        val target: View = if (quickOpen) quick else panel
        val lp = target.layoutParams as LayoutParams
        lp.height = if (typing) (196 * d).toInt() else kv.preferredHeight()
        target.layoutParams = lp
        kv.visibility = if (typing) VISIBLE else GONE
    }

    fun openClipboard() {
        if (panelOpen) return
        closeMix()
        closeTools()
        closeStatus()
        closeQuick()
        panelOpen = true
        typing = false
        applyHeights()
        panel.visibility = VISIBLE
        panel.alpha = 0f
        panel.translationY = 24f * d
        panel.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        panel.open()
        syncSecure()
    }

    fun closeClipboard() {
        if (!panelOpen) return
        panelOpen = false
        typing = false
        panel.animate().cancel()
        panel.close()
        panel.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
        refreshSuggestion()
    }

    // ---- tool list ----

    fun openTools() {
        if (toolsOpen) return
        closeMix()
        closeStatus()
        closeClipboard()
        closeStickers()
        closeQuick()
        toolsOpen = true
        typing = false
        val lp = tools.layoutParams as LayoutParams
        lp.height = kv.preferredHeight()
        tools.layoutParams = lp
        tools.open()
        tools.visibility = VISIBLE
        tools.alpha = 0f
        tools.translationY = 24f * d
        tools.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        refreshSuggestion()
    }

    fun closeTools() {
        if (!toolsOpen) return
        toolsOpen = false
        typing = false
        tools.animate().cancel()
        tools.close()
        tools.visibility = GONE
        kv.visibility = VISIBLE
        refreshSuggestion()
    }

    override fun onToolRun(id: Int) {
        // the theme button changes the look right here, so the list stays open; everything else leaves it first
        if (id != Tools.THEME) closeTools()
        kv.runTool(id)
    }

    override fun onToolsClose() {
        closeTools()
    }

    override fun onPinsChanged() {
        kv.reloadTools()
    }

    // ---- zeta translate ----

    private val ui = Handler(Looper.getMainLooper())
    private val clearStatus = Runnable { kv.setStatus(null) }

    private fun showStatus(msg: String, kind: Int, ms: Long) {
        ui.removeCallbacks(clearStatus)
        kv.setStatus(msg, kind)
        if (ms > 0) ui.postDelayed(clearStatus, ms)
    }

    /** Stops a running translation or voice typing and clears its message from the toolbar. */
    fun cancelWork() {
        Translator.cancel()
        voice.cancel()
        ui.removeCallbacks(clearStatus)
        kv.setStatus(null)
    }

    /**
     * Translate button: takes the selected text (or the whole message when nothing is selected), translates it into the
     * language chosen in the Zeta Board app and swaps the translation in. Progress and problems show on the toolbar.
     */
    private fun translateAction() {
        if (!out.translateAllowed()) {
            showStatus("Translate is off in password fields", KeyboardView.STATUS_ERROR, 3000)
            return
        }
        val src = out.translateSource()
        if (src == null) {
            showStatus("Tap a text box first", KeyboardView.STATUS_ERROR, 3000)
            return
        }
        if (src.problem != null) {
            showStatus(src.problem, KeyboardView.STATUS_ERROR, 3200)
            return
        }
        if (src.text.length > Translator.MAX_CHARS) {
            showStatus("Too long. Select up to ${Translator.MAX_CHARS} characters", KeyboardView.STATUS_ERROR, 3500)
            return
        }
        val lang = Langs.target(context)
        showStatus("Translating to ${lang.name}\u2026", KeyboardView.STATUS_BUSY, 0)
        Translator.run(src.text, lang.code) { res, err ->
            if (err != null) {
                showStatus(err.message ?: "Translation failed", KeyboardView.STATUS_ERROR, 3500)
            } else if (res == null) {
                showStatus("Translation failed", KeyboardView.STATUS_ERROR, 3500)
            } else if (Langs.same(res.detected, lang.code)) {
                showStatus("Already in ${lang.name}", KeyboardView.STATUS_INFO, 2200)
            } else if (out.applyTranslation(src, res.text)) {
                ui.removeCallbacks(clearStatus)
                kv.setStatus(null)
                refreshSuggestion()
            } else {
                showStatus("The text changed. Nothing was replaced", KeyboardView.STATUS_ERROR, 3500)
            }
        }
    }

    // ---- voice typing ----

    private val voice = Voice(ctx, object : Voice.Callback {
        override fun onVoiceHeard(partial: String) {
            ui.removeCallbacks(clearStatus)
            var shown = if (partial.length > 36) partial.takeLast(36) else partial
            if (shown.isNotEmpty() && Character.isLowSurrogate(shown[0])) shown = shown.substring(1)
            if (partial.length > 36) shown = "\u2026$shown"
            kv.setStatus(shown.ifEmpty { "Listening\u2026 speak now" }, KeyboardView.STATUS_BUSY, R.drawable.ic_mic)
        }

        override fun onVoiceText(text: String) {
            val prev = out.charBeforeCursor()
            val space = prev != null && !prev.isWhitespace() && "([{\"'\u201C\u2018".indexOf(prev) < 0
            out.commit(if (space) " $text" else text)
            quickSuppress = false
            refreshSuggestion()
        }

        override fun onVoiceEnded(problem: String?, needMic: Boolean) {
            ui.removeCallbacks(clearStatus)
            if (problem == null) {
                kv.setStatus(null)
            } else if (needMic) {
                out.requestMic()
                showStatus(problem, KeyboardView.STATUS_INFO, 4000)
            } else {
                showStatus(problem, KeyboardView.STATUS_ERROR, 3500)
            }
            refreshSuggestion()
        }
    })

    /** Mic button: starts voice typing (the toolbar message stops it). Speech goes into the focused field. */
    private fun voiceAction() {
        if (voice.active) {
            voice.stop()
            return
        }
        if (!out.voiceAllowed()) {
            showStatus("Voice typing is off in password fields", KeyboardView.STATUS_ERROR, 3000)
            return
        }
        if (!Voice.available(context)) {
            showStatus("Voice typing is not available. Install or enable the Google app", KeyboardView.STATUS_ERROR, 4000)
            return
        }
        if (!Voice.hasMic(context)) {
            out.requestMic()
            showStatus("Allow the microphone, then tap the mic again", KeyboardView.STATUS_INFO, 4000)
            return
        }
        Translator.cancel()
        ui.removeCallbacks(clearStatus)
        kv.setStatus("Listening\u2026 speak now", KeyboardView.STATUS_BUSY, R.drawable.ic_mic)
        voice.start()
    }

    // ---- singlish typing ----

    /** The roman letters typed so far for the word being written (shown in the field as Sinhala, still underlined). */
    private val singBuf = StringBuilder()

    /** True while a word that started with a dot (a Quick Text shortcut) is typed as plain letters. */
    private var singRaw = false

    /** True when typed letters should become Sinhala: switched on, a normal text field, and no panel is taking the keys. */
    private fun singlishActive(): Boolean =
        Prefs.getB(context, "singlish", false) && out.quickAllowed() && !routed()

    // ---- word suggestions (Smart typing) ----

    private var curWords: List<String> = emptyList()

    private fun wordsOn(): Boolean = Prefs.getB(context, "word_suggest", true)

    private fun clearWords() {
        if (curWords.isEmpty()) return
        curWords = emptyList()
        kv.setWords(curWords)
    }

    private fun updateWords() {
        if (singBuf.isEmpty() || !wordsOn()) {
            clearWords()
            return
        }
        curWords = WordSuggest.suggest(context, singBuf.toString())
        kv.setWords(curWords)
    }

    override fun onWord(i: Int) {
        val w = curWords.getOrNull(i) ?: return
        if (singBuf.isEmpty()) return
        out.setComposing(w)
        out.finishComposing()
        out.commit(" ")
        singBuf.setLength(0)
        WordSuggest.learn(context, w)
        clearWords()
        scheduleSuggestion()
    }

    // ---- auto-fix (Smart typing) ----

    private class Fix(val original: String, val fixed: String, val delim: String)

    private var lastFix: Fix? = null

    /** A word that was just put back by backspace: it is not fixed again the next time it ends. */
    private var skipFix: String? = null

    /** If the word before the cursor is a known typo and [delim] ends it, replaces it. Returns what was done, or null. */
    private fun tryAutoFix(delim: String): Fix? {
        if (!Prefs.getB(context, "autofix", true) || !out.quickAllowed()) return null
        val before = try {
            out.textBefore(40)?.toString()
        } catch (e: Exception) {
            null
        } ?: return null
        var i = before.length
        while (i > 0 && AutoFix.isWordChar(before[i - 1])) i--
        while (i < before.length && before[i] == '\'') i++
        val word = before.substring(i)
        val fixed = AutoFix.fix(word) ?: return null
        val skip = skipFix
        if (skip != null) {
            skipFix = null
            if (skip.equals(word, true)) return null
        }
        if (!out.replaceBefore(word.length, fixed)) return null
        return Fix(word, fixed, delim)
    }

    /** Backspace right after an auto-fix: puts back what the user typed. True when it did. */
    private fun undoAutoFix(f: Fix): Boolean {
        val want = f.fixed + f.delim
        val now = try {
            out.textBefore(want.length)?.toString()
        } catch (e: Exception) {
            null
        }
        if (now != want) return false
        if (!out.replaceBefore(want.length, f.original)) return false
        skipFix = f.original
        return true
    }

    /** Ends the word being written: the Sinhala stays in the field as normal text. */
    fun flushSinglish() {
        singRaw = false
        if (singBuf.isEmpty()) return
        if (wordsOn()) WordSuggest.learn(context, Singlish.convert(singBuf.toString()))
        singBuf.setLength(0)
        clearWords()
        out.finishComposing()
    }

    /** A new text field started (or the keyboard closed): forget the word, the old field is gone. */
    fun resetSinglish() {
        singBuf.setLength(0)
        singRaw = false
        lastFix = null
        skipFix = null
        clearWords()
    }

    /**
     * The cursor or text changed. When the word we are writing is no longer right before the cursor (the user moved the
     * cursor, or the app cleared the box after Send), forget it so the next letters start a fresh word.
     */
    fun onSelectionChanged() {
        if (singBuf.isEmpty()) return
        val exp = Singlish.convert(singBuf.toString())
        val before = try {
            out.textBefore(exp.length)?.toString()
        } catch (e: Exception) {
            null
        }
        if (before != exp) {
            singBuf.setLength(0)
            clearWords()
        }
    }

    private fun singlishToggle() {
        val on = !Prefs.getB(context, "singlish", false)
        Prefs.setB(context, "singlish", on)
        kv.onSinglishChanged()
        if (on) {
            showStatus("Sinhala phonetic ON \u2014 type mama gedara yanawa", KeyboardView.STATUS_INFO, 2600)
        } else {
            showStatus("Sinhala phonetic OFF", KeyboardView.STATUS_INFO, 1800)
        }
    }

    // ---- quick text ----

    /**
     * Shows or hides the Quick Text suggestion on the toolbar. Cheap to call often: the toolbar only redraws when it changes.
     * [reset] is true when a new text field starts.
     */
    private val sugRun = Runnable { refreshSuggestion() }

    /** Typing fast fires a selection update per key; look up the shortcut once the burst pauses instead of asking the app every time. */
    fun scheduleSuggestion() {
        ui.removeCallbacks(sugRun)
        ui.postDelayed(sugRun, 70L)
    }

    fun refreshSuggestion(reset: Boolean = false) {
        if (reset) quickSuppress = false
        val m = if (quickSuppress || panelOpen || stickersOpen || quickOpen || toolsOpen || mixOpen) null else try {
            out.quickSuggestion()
        } catch (e: Exception) {
            null
        }
        kv.setSuggestion(m)
    }

    fun openQuick() {
        if (quickOpen) return
        closeMix()
        closeTools()
        closeStatus()
        closeClipboard()
        closeStickers()
        quickOpen = true
        typing = false
        applyHeights()
        quick.visibility = VISIBLE
        quick.alpha = 0f
        quick.translationY = 24f * d
        quick.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        quick.open(!out.quickAllowed())
        syncSecure()
    }

    fun closeQuick() {
        if (!quickOpen) return
        quickOpen = false
        typing = false
        quick.animate().cancel()
        quick.close()
        quick.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
        refreshSuggestion()
    }

    override fun onQuickPick(text: String): Boolean {
        if (!out.insertQuick(text)) return false
        closeQuick()
        return true
    }

    override fun onQuickClose() {
        closeQuick()
    }

    override fun onQuickManage() {
        out.openQuickManager()
        closeQuick()
    }

    override fun onQuickTyping(active: Boolean) {
        typing = active
        if (active) kv.startInput(0, 0)
        applyHeights()
    }

    // ---- emoji mix ----

    fun openMix() {
        if (mixOpen) return
        closeTools()
        closeStatus()
        closeClipboard()
        closeStickers()
        closeQuick()
        mixOpen = true
        typing = false
        val lp = emojiMix.layoutParams as LayoutParams
        lp.height = kv.preferredHeight()
        emojiMix.layoutParams = lp
        emojiMix.open()
        emojiMix.visibility = VISIBLE
        emojiMix.alpha = 0f
        emojiMix.translationY = 24f * d
        emojiMix.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        refreshSuggestion()
    }

    fun closeMix() {
        if (!mixOpen) return
        mixOpen = false
        typing = false
        emojiMix.animate().cancel()
        emojiMix.close()
        emojiMix.visibility = GONE
        kv.visibility = VISIBLE
        refreshSuggestion()
    }

    override fun onMixPick(text: String): Boolean {
        if (!out.insertQuick(text)) return false
        closeMix()
        return true
    }

    override fun onMixClose() {
        closeMix()
    }

    // ---- sticker maker ----

    private val hubListener = object : StickerHub.Listener {
        override fun onStatus(text: String) {
            stickers.setStatus(text)
        }

        override fun onDone(f: File, note: String?) {
            // if the keyboard is on screen the user sees the sticker right now, otherwise show it next time
            StickerHub.pendingShow = !(isAttachedToWindow && windowVisibility == View.VISIBLE)
            if (!stickersOpen) openStickers()
            stickers.onNew(f, note)
        }

        override fun onFail(msg: String) {
            stickers.setStatus(null)
            stickers.flash(msg)
        }

        override fun onIdle() {
            stickers.setStatus(null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        StickerHub.listener = hubListener
    }

    override fun onDetachedFromWindow() {
        if (StickerHub.listener === hubListener) StickerHub.listener = null
        super.onDetachedFromWindow()
    }

    fun openStickers() {
        if (stickersOpen) return
        closeMix()
        closeTools()
        closeStatus()
        closeClipboard()
        closeQuick()
        StickerHub.pendingShow = false
        stickersOpen = true
        typing = false
        val lp = stickers.layoutParams as LayoutParams
        lp.height = kv.preferredHeight()
        stickers.layoutParams = lp
        stickers.visibility = VISIBLE
        stickers.alpha = 0f
        stickers.translationY = 24f * d
        stickers.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        stickers.open()
        syncSecure()
    }

    fun closeStickers() {
        if (!stickersOpen) return
        stickersOpen = false
        stickers.animate().cancel()
        stickers.close()
        stickers.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
        refreshSuggestion()
    }

    /** Called every time the keyboard shows up: opens the sticker panel if a photo/sticker is waiting for it. */
    fun onStartInput() {
        if (StatusHub.pendingShow) openStatus() else if (StickerHub.pendingShow) openStickers()
    }

    // ---- status saver ----

    fun openStatus() {
        StatusHub.pendingShow = false
        if (statusOpen) {
            status.reload()
            return
        }
        closeMix()
        closeTools()
        closeClipboard()
        closeStickers()
        closeQuick()
        statusOpen = true
        typing = false
        val lp = status.layoutParams as LayoutParams
        lp.height = kv.preferredHeight()
        status.layoutParams = lp
        status.visibility = VISIBLE
        status.alpha = 0f
        status.translationY = 24f * d
        status.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        status.open()
        syncSecure()
    }

    fun closeStatus() {
        if (!statusOpen) return
        statusOpen = false
        status.animate().cancel()
        status.close()
        status.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
        refreshSuggestion()
    }

    override fun onStatusSetup(kind: String) {
        StatusHub.pendingShow = true
        out.openStatusSetup(kind)
    }

    override fun onStatusSend(f: File, mime: String) {
        if (out.sendMedia(f, mime)) {
            status.flash("Sent")
        } else {
            status.flash("This app can't take it directly \u2014 choose where to send")
            out.shareMedia(listOf(f), listOf(mime))
        }
    }

    override fun onStatusShare(files: List<File>, mimes: List<String>) {
        out.shareMedia(files, mimes)
    }

    override fun onStatusNeedPermission() {
        StatusHub.pendingShow = true
        out.requestStoragePermission()
    }

    override fun onStatusClose() {
        closeStatus()
    }

    override fun onPickPhoto() {
        StickerHub.pendingShow = true
        out.pickPhoto()
    }

    override fun onUseClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var uri: Uri? = null
        try {
            val clip = cm.primaryClip
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri ?: continue
                    val type = context.contentResolver.getType(u) ?: ""
                    if (type.startsWith("image/")) {
                        uri = u
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
        if (uri == null) {
            stickers.flash("No copied image found \u2014 copy a photo first")
            return
        }
        StickerHub.submitUri(context, uri)
    }

    override fun onSend(f: File) {
        if (out.sendSticker(f)) {
            stickers.flash("Sticker sent")
        } else {
            stickers.flash("This app can't take stickers directly \u2014 choose where to send")
            out.shareSticker(f)
        }
    }

    private fun routed(): Boolean = (panelOpen || quickOpen) && typing

    // ---- KeyboardView.Listener ----

    override fun onText(s: String) {
        if (routed()) {
            if (quickOpen) quick.insert(s) else panel.insert(s)
            return
        }
        quickSuppress = false
        if (singlishActive()) {
            val letter = s.length == 1 && Singlish.isLetter(s[0])
            if (letter && (singRaw || (singBuf.isEmpty() && out.charBeforeCursor() == '.'))) {
                // a word that starts with a dot is a Quick Text shortcut (.welcome): type it as plain letters
                singRaw = true
            } else if (letter) {
                singBuf.append(s)
                out.setComposing(Singlish.convert(singBuf.toString()))
                updateWords()
                scheduleSuggestion()
                return
            } else {
                flushSinglish()
                singRaw = false
            }
        }
        // Quick Text: a typed delimiter right after a shortcut expands it (the IME decides if it is allowed)
        if (s.length == 1 && QuickText.isDelimiter(s[0]) && out.expand(s)) {
            refreshSuggestion() // the shortcut was just replaced: clear the toolbar suggestion right away
            return
        }
        val fix = if (s.length == 1 && AutoFix.isDelimiter(s[0])) tryAutoFix(s) else null
        val st = Prefs.getI(context, "textstyle", 0)
        out.commit(if (st != 0) TextStyles.convert(st, s) else s)
        lastFix = fix
        scheduleSuggestion()
    }

    override fun onAction(a: Int) {
        if (a == KeyboardView.DEL && !routed()) {
            val f = lastFix
            lastFix = null
            if (f != null && singBuf.isEmpty() && undoAutoFix(f)) {
                scheduleSuggestion()
                return
            }
        } else if (a != KeyboardView.DEL) {
            lastFix = null
        }
        if (a == KeyboardView.DEL && !routed() && singBuf.isNotEmpty()) {
            // Singlish: remove the last typed letter and redraw the Sinhala
            singBuf.setLength(singBuf.length - 1)
            if (singBuf.isEmpty()) {
                out.setComposing("")
                out.finishComposing()
            } else {
                out.setComposing(Singlish.convert(singBuf.toString()))
            }
            updateWords()
            scheduleSuggestion()
            return
        }
        if (a != KeyboardView.DEL && a != KeyboardView.STATUS) flushSinglish()
        when (a) {
            KeyboardView.DEL -> if (routed()) (if (quickOpen) quick.backspace() else panel.backspace()) else {
                quickSuppress = false
                out.del()
                scheduleSuggestion()
            }
            KeyboardView.ENTER -> if (routed()) (if (quickOpen) quick.enter() else panel.enter()) else {
                quickSuppress = false
                out.enter()
                scheduleSuggestion()
            }
            KeyboardView.LEFT -> if (!routed()) {
                out.cursor(-1)
                scheduleSuggestion()
            }
            KeyboardView.RIGHT -> if (!routed()) {
                out.cursor(1)
                scheduleSuggestion()
            }
            KeyboardView.TOOLS -> openTools()
            KeyboardView.SUGGEST -> {
                if (out.applyQuick()) quickSuppress = true
                refreshSuggestion()
            }
            KeyboardView.CLIPBOARD -> openClipboard()
            KeyboardView.STICKER -> openStickers()
            KeyboardView.STATUSES -> openStatus()
            KeyboardView.QUICK -> openQuick()
            KeyboardView.TRANSLATE -> translateAction()
            KeyboardView.VOICE -> voiceAction()
            KeyboardView.SINGLISH -> singlishToggle()
            KeyboardView.MIX -> openMix()
            KeyboardView.STATUS -> if (voice.active) {
                kv.setStatus("Finishing\u2026", KeyboardView.STATUS_BUSY, R.drawable.ic_mic)
                voice.stop()
            } else {
                cancelWork()
            }
            KeyboardView.SETTINGS -> out.settings()
        }
    }

    // ---- ClipPanel.Callback ----

    override fun onPaste(text: String) {
        out.commit(text)
        closeClipboard()
    }

    override fun onCopy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Board", text))
        } catch (e: Exception) {
        }
        ClipRepo.add(text, Prefs.getI(context, "clip_max", 100))
    }

    override fun onClose() {
        if (stickersOpen) closeStickers() else closeClipboard()
    }

    override fun onTyping(active: Boolean) {
        typing = active
        if (active) kv.startInput(0, 0)
        applyHeights()
    }
}

/** Keeps obviously sensitive copies (OTP codes, card numbers) out of the history. */
object ClipGuard {
    private val otp = Regex("^\\s*\\d{4,8}\\s*$")
    private val otpSplit = Regex("^\\s*\\d{3}[ -]\\d{3}\\s*$")

    fun looksSensitive(t: String): Boolean {
        if (otp.matches(t) || otpSplit.matches(t)) return true
        val digits = t.replace(" ", "").replace("-", "")
        if (digits.length in 13..19 && digits.all { it.isDigit() } && luhn(digits)) return true
        return false
    }

    private fun luhn(s: String): Boolean {
        var sum = 0
        var alt = false
        for (i in s.length - 1 downTo 0) {
            var n = s[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
