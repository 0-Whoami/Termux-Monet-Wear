package com.termux.terminal

import android.util.Base64
import com.termux.terminal.KeyHandler.getCodeFromTermcap
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BLINK
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BOLD
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_DIM
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_INVERSE
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_ITALIC
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
import com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
import com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND
import com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR
import com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND
import com.termux.terminal.TextStyle.NUM_INDEXED_COLORS
import com.termux.terminal.TextStyle.decodeEffect
import com.termux.terminal.TextStyle.encode
import java.util.Arrays
import java.util.Locale
import java.util.regex.Pattern
import kotlin.experimental.and
import kotlin.math.max
import kotlin.math.min

/**
 * Renders text into a console. Contains all the terminal-specific knowledge and state. Emulates a subset of the X Window
 * System xterm terminal, which in turn is an emulator for a subset of the Digital Equipment Corporation vt100 terminal.
 */
class TerminalEmulator(
    /**
     * The terminal session this emulator is bound to.
     */
    private val mSession: TerminalSession,
    columns: Int,
    rows: Int,
    transcriptRows: Int
) {
    val mColors: TerminalColors = TerminalColors()

    /**
     * The normal console buffer. Stores the characters that appear on the console of the emulated terminal.
     */
    private val mMainBuffer: TerminalBuffer =
        TerminalBuffer(columns, transcriptRows, rows)

    /**
     * The alternate console buffer, exactly as large as the display and contains no additional saved lines (so that when
     * the alternate console buffer is active, you cannot scroll back to view saved lines).
     */
    private val mAltBuffer: TerminalBuffer = TerminalBuffer(columns, transcriptRows, rows)

    /**
     * Holds the arguments of the current escape sequence.
     */
    private val mArgs = IntArray(MAX_ESCAPE_PARAMETERS)

    /**
     * Holds OSC and device control arguments, which can be strings.
     */
    private val mOSCOrDeviceControlArgs = StringBuilder()
    private val mSavedStateMain = SavedScreenState()
    private val mSavedStateAlt = SavedScreenState()

    private val mUtf8InputBuffer = ByteArray(4)

    /**
     * The number of character rows and columns in the terminal console.
     */

    var mRows: Int = rows


    var mColumns: Int = columns

    /**
     * Get the terminal session's title (null if not set).
     */
    /**
     * If processing first character of first parameter of [.ESC_CSI].
     */
    private var mIsCSIStart = false

    /**
     * The last character processed of a parameter of [.ESC_CSI].
     */
    private var mLastCSIArg: Int? = null

    /**
     * The cursor position. Between (0,0) and (mRows-1, mColumns-1).
     */
    private var mCursorRow = 0
    private var mCursorCol = 0

    /**
     * The terminal cursor styles.
     */
    var cursorStyle: Int = 0
        private set

    /**
     * The current console buffer, pointing at either [.mMainBuffer] or [.mAltBuffer].
     */
    var screen: TerminalBuffer = mMainBuffer
        private set

    /**
     * Keeps track of the current argument of the current escape sequence. Ranges from 0 to MAX_ESCAPE_PARAMETERS-1.
     */
    private var mArgIndex = 0

    /**
     * True if the current escape sequence should continue, false if the current escape sequence should be terminated.
     * Used when parsing a single character.
     */
    private var mContinueSequence = false

    /**
     * The current state of the escape sequence state machine. One of the ESC_* constants.
     */
    private var mEscapeState = 0

    /**
     * [...](http://www.vt100.net/docs/vt102-ug/table5-15.html)
     */
    private var mUseLineDrawingG0 = false
    private var mUseLineDrawingG1 = false
    private var mUseLineDrawingUsesG0 = true

    /**
     * @see TerminalEmulator.mapDecSetBitToInternalBit
     */
    private var mCurrentDecSetFlags = 0
    private var mSavedDecSetFlags = 0

    /**
     * If insert mode (as opposed to replace mode) is active. In insert mode new characters are inserted, pushing
     * existing text to the right. Characters moved past the right margin are lost.
     */
    private var mInsertMode = false

    /**
     * An array of tab stops. mTabStop is true if there is a tab stop set for column i.
     */
    private var mTabStop: BooleanArray = BooleanArray(columns)

    /**
     * Top margin of console for scrolling ranges from 0 to mRows-2. Bottom margin ranges from mTopMargin + 2 to mRows
     * (Defines the first row after the scrolling region). Left/right margin in [0, mColumns].
     */
    private var mTopMargin = 0
    private var mBottomMargin = 0
    private var mLeftMargin = 0
    private var mRightMargin = 0

    /**
     * If the next character to be emitted will be automatically wrapped to the next line. Used to disambiguate the case
     * where the cursor is positioned on the last column (mColumns-1). When standing there, a written character will be
     * output in the last column, the cursor not moving but this flag will be set. When outputting another character
     * this will move to the next line.
     */
    private var mAboutToAutoWrap = false

    /**
     * If the cursor blinking is enabled. It requires cursor itself to be enabled, which is controlled
     * byt whether [.DECSET_BIT_CURSOR_ENABLED] bit is set or not.
     */
    private var mCursorBlinkingEnabled = false

    /**
     * If currently cursor should be in a visible state or not if [.mCursorBlinkingEnabled]
     * is `true`.
     */
    private var mCursorBlinkState = false

    /**
     * Current foreground and background colors. Can either be a color index in [0,259] or a truecolor (24-bit) value.
     * For a 24-bit value the top byte (0xff000000) is set.
     *
     * see TextStyle
     */
    private var mForeColor = 0
    private var mBackColor = 0

    /**
     * Current TextStyle effect.
     */
    private var mEffect = 0

    /**
     * The number of scrolled lines since last calling [.clearScrollCounter]. Used for moving selection up along
     * with the scrolling text.
     */
    var scrollCounter: Int = 0
        private set
    private var mUtf8ToFollow: Byte = 0
    private var mUtf8Index: Byte = 0
    private var mLastEmittedCodePoint = -1

    init {
        this.reset()
    }

    private fun isDecsetInternalBitSet(bit: Int): Boolean {
        return (this.mCurrentDecSetFlags and bit) != 0
    }

    private fun setDecsetinternalBit(internalBit: Int, set: Boolean) {
        if (set) {
            // The mouse modes are mutually exclusive.
            if (internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                this.setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
            } else if (DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT == internalBit) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
            }
        }
        mCurrentDecSetFlags = if (set) {
            mCurrentDecSetFlags or internalBit
        } else {
            mCurrentDecSetFlags and internalBit.inv()
        }
    }

    fun updateTermuxTerminalSessionClientBase() {
        mCursorBlinkState = true
    }

    val isAlternateBufferActive: Boolean
        get() = screen == mAltBuffer

    /**
     * @param mouseButton one of the MOUSE_* constants of this class.
     */
    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        var column1 = if (1 > column) 1 else column
        var row1 = if (1 > row) 1 else row
        if (column1 > mColumns) column1 = mColumns
        if (row1 > mRows) row1 = mRows

        if (!(MOUSE_LEFT_BUTTON_MOVED == mouseButton && !this.isDecsetInternalBitSet(
                DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
            )) && this.isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)
        ) {
            mSession.write(
                String.format(
                    Locale.US,
                    "\u001b[<%d;%d;%d" + (if (pressed) 'M' else 'm'),
                    mouseButton,
                    column1,
                    row1
                )
            )
        } else {
            // 3 for release of all buttons.
            val mouseButton1 = if (pressed) mouseButton else 3
            // Clip to console, and clip to the limits of 8-bit data.
            val out_of_bounds = 255 - 32 < column1 || 255 - 32 < row1
            if (!out_of_bounds) {
                val data = byteArrayOf(
                    '\u001b'.code.toByte(),
                    '['.code.toByte(),
                    'M'.code.toByte(),
                    (32 + mouseButton1).toByte(),
                    (32 + column1).toByte(),
                    (32 + row1).toByte()
                )
                mSession.write(data, 0, data.size)
            }
        }
    }

    fun resize(columns: Int, rows: Int) {
        if (this.mRows == rows && this.mColumns == columns) {
            return
        }
        if (this.mRows != rows) {
            this.mRows = rows
            this.mTopMargin = 0
            this.mBottomMargin = this.mRows
        }
        if (this.mColumns != columns) {
            val oldColumns = this.mColumns
            this.mColumns = columns
            val oldTabStop = this.mTabStop
            this.mTabStop = BooleanArray(this.mColumns)
            this.setDefaultTabStops()
            val toTransfer = min(oldColumns, columns)
            System.arraycopy(oldTabStop, 0, this.mTabStop, 0, toTransfer)
            this.mLeftMargin = 0
            this.mRightMargin = this.mColumns
        }
        this.resizeScreen()
    }

    private fun resizeScreen() {
        val cursor = intArrayOf(this.mCursorCol, this.mCursorRow)
        val newTotalRows =
            if (this.screen == this.mAltBuffer) this.mRows else mMainBuffer.mTotalRows
        screen.resize(
            this.mColumns, this.mRows, newTotalRows, cursor,
            style,
            isAlternateBufferActive
        )
        this.mCursorCol = cursor[0]
        this.mCursorRow = cursor[1]
    }

    var cursorRow: Int
        get() = this.mCursorRow
        private set(row) {
            this.mCursorRow = row
            this.mAboutToAutoWrap = false
        }

    var cursorCol: Int
        get() = this.mCursorCol
        private set(col) {
            this.mCursorCol = col
            this.mAboutToAutoWrap = false
        }


    val isReverseVideo: Boolean
        get() = this.isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)

    private val isCursorEnabled: Boolean
        get() = !this.isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED)

    fun shouldCursorBeVisible(): Boolean {
        return if (this.isCursorEnabled) false
        else !this.mCursorBlinkingEnabled || this.mCursorBlinkState
    }

    fun setCursorBlinkState(cursorBlinkState: Boolean) {
        mCursorBlinkState = cursorBlinkState
    }

    val isKeypadApplicationMode: Boolean
        get() = this.isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)

    val isCursorKeysApplicationMode: Boolean
        get() = this.isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS)

    val isMouseTrackingActive: Boolean
        /**
         * If mouse events are being sent as escape codes to the terminal.
         */
        get() = this.isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || this.isDecsetInternalBitSet(
            DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
        )

    private fun setDefaultTabStops() {
        for (i in 0 until this.mColumns) mTabStop[i] = 0 == (i and 7) && 0 != i
    }

    /**
     * Accept bytes (typically from the pseudo-teletype) and process them.
     *
     * @param buffer a byte array containing the bytes to be processed
     * @param length the number of bytes in the array to process
     */
    fun append(buffer: ByteArray, length: Int) {
        for (i in 0 until length) this.processByte(buffer[i])
    }

    /** Called after getting data from session*/
    private fun processByte(byteToProcess: Byte) {
        if (0 < mUtf8ToFollow) {
            if (128 == (byteToProcess.toInt() and 192)) {
                // 10xxxxxx, a continuation byte.
                mUtf8InputBuffer[mUtf8Index.toInt()] = byteToProcess
                mUtf8Index++
                --mUtf8ToFollow
                if (0.toByte() == mUtf8ToFollow) {
                    val firstByteMask =
                        (if (2.toByte() == mUtf8Index) 31 else (if (3.toByte() == mUtf8Index) 15 else 7)).toByte()
                    var codePoint = (mUtf8InputBuffer[0] and firstByteMask).toInt()
                    for (i in 1 until this.mUtf8Index) codePoint =
                        ((codePoint shl 6) or (mUtf8InputBuffer[i] and 63.toByte()).toInt())
                    if (((127 >= codePoint) && 1 < mUtf8Index) || (2047 > codePoint && 2 < mUtf8Index) || (65535 > codePoint && 3 < mUtf8Index)) {
                        // Overlong encoding.
                        codePoint = UNICODE_REPLACEMENT_CHAR
                    }
                    this.mUtf8Index = 0
                    mUtf8ToFollow = 0
                    if (0x80 > codePoint || 0x9F < codePoint) {
                        codePoint = when (Character.getType(codePoint).toByte()) {
                            Character.UNASSIGNED, Character.SURROGATE -> UNICODE_REPLACEMENT_CHAR
                            else -> codePoint
                        }
                        this.processCodePoint(codePoint)
                    }
                }
            } else {
                // Not a UTF-8 continuation byte so replace the entire sequence up to now with the replacement char:
                this.mUtf8ToFollow = 0
                this.mUtf8Index = 0
                this.emitCodePoint(UNICODE_REPLACEMENT_CHAR)

                this.processByte(byteToProcess)
            }
        } else {
            val byteToProcess_b = byteToProcess.toInt()
            if (0 == (byteToProcess_b and 128)) {
                // The leading bit is not set so it is a 7-bit ASCII character.
                this.processCodePoint(byteToProcess_b)
                return
            } else if (192 == (byteToProcess_b and 224)) {
                // 110xxxxx, a two-byte sequence.
                this.mUtf8ToFollow = 1
            } else if (224 == (byteToProcess_b and 240)) {
                // 1110xxxx, a three-byte sequence.
                this.mUtf8ToFollow = 2
            } else if (240 == (byteToProcess_b and 248)) {
                // 11110xxx, a four-byte sequence.
                this.mUtf8ToFollow = 3
            } else {
                // Not a valid UTF-8 sequence start, signal invalid data:
                this.processCodePoint(UNICODE_REPLACEMENT_CHAR)
                return
            }
            mUtf8InputBuffer[mUtf8Index++.toInt()] = byteToProcess
        }
    }

    private fun processCodePoint(b: Int) {
        when (b) {
            0 -> {}
            7 -> if (ESC_OSC == mEscapeState) doOsc(b)

            8 -> if (mLeftMargin == mCursorCol) {
                // Jump to previous line if it was auto-wrapped.
                val previousRow = mCursorRow - 1
                if (0 <= previousRow && screen.getLineWrap(previousRow)) {
                    screen.clearLineWrap(previousRow)
                    setCursorRowCol(previousRow, mRightMargin - 1)
                }
            } else {
                cursorCol = mCursorCol - 1
            }

            9 ->
                mCursorCol = nextTabStop(1)

            10, 11, 12 -> doLinefeed()

            13 -> cursorCol = mLeftMargin

            14 -> mUseLineDrawingUsesG0 = false
            15 -> mUseLineDrawingUsesG0 = true
            24, 26 -> if (ESC_NONE != mEscapeState) {
                // FIXME: What is this??
                mEscapeState = ESC_NONE
                emitCodePoint(127)
            }

            27 ->                 // Starts an escape sequence unless we're parsing a string
                if (ESC_P == mEscapeState) {
                    // XXX: Ignore escape when reading device control sequence, since it may be part of string terminator.
                    return
                } else if (ESC_OSC != mEscapeState) {
                    startEscapeSequence()
                } else {
                    doOsc(b)
                }

            else -> {
                mContinueSequence = false
                when (mEscapeState) {
                    ESC_NONE -> if (32 <= b) emitCodePoint(b)
                    ESC -> doEsc(b)
                    ESC_POUND -> doEscPound(b)
                    ESC_SELECT_LEFT_PAREN -> mUseLineDrawingG0 = ('0'.code == b)
                    ESC_SELECT_RIGHT_PAREN -> mUseLineDrawingG1 = ('0'.code == b)
                    ESC_CSI -> doCsi(b)
                    ESC_CSI_EXCLAMATION -> if ('p'.code == b) {
                        // Soft terminal reset (DECSTR, http://vt100.net/docs/vt510-rm/DECSTR).
                        reset()
                    } else {
                        finishSequence()
                    }

                    ESC_CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    ESC_CSI_BIGGERTHAN -> doCsiBiggerThan(b)
                    ESC_CSI_DOLLAR -> {
                        val originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
                        val effectiveTopMargin = if (originMode) mTopMargin else 0
                        val effectiveBottomMargin = if (originMode) mBottomMargin else mRows
                        val effectiveLeftMargin = if (originMode) mLeftMargin else 0
                        val effectiveRightMargin = if (originMode) mRightMargin else mColumns
                        when (b.toChar()) {
                            'v' -> {
                                val topSource = min(
                                    (this.getArg(0, 1, true) - 1 + effectiveTopMargin),
                                    mRows
                                )
                                val leftSource = min(
                                    (this.getArg(1, 1, true) - 1 + effectiveLeftMargin),
                                    mColumns
                                )
                                // Inclusive, so do not subtract one:
                                val bottomSource = min(
                                    max(
                                        (this.getArg(
                                            2,
                                            this.mRows,
                                            true
                                        ) + effectiveTopMargin), topSource
                                    ),
                                    mRows
                                )
                                val rightSource = min(
                                    max(
                                        (this.getArg(
                                            3,
                                            this.mColumns,
                                            true
                                        ) + effectiveLeftMargin), leftSource
                                    ),
                                    mColumns
                                )
                                // int sourcePage = getArg(4, 1, true);
                                val destionationTop = min(
                                    (this.getArg(5, 1, true) - 1 + effectiveTopMargin),
                                    mRows
                                )
                                val destinationLeft = min(
                                    (this.getArg(6, 1, true) - 1 + effectiveLeftMargin),
                                    mColumns
                                )
                                // int destinationPage = getArg(7, 1, true);
                                val heightToCopy = min(
                                    (this.mRows - destionationTop),
                                    (bottomSource - topSource)
                                )
                                val widthToCopy = min(
                                    (this.mColumns - destinationLeft),
                                    (rightSource - leftSource)
                                )
                                screen.blockCopy(
                                    leftSource,
                                    topSource,
                                    widthToCopy,
                                    heightToCopy,
                                    destinationLeft,
                                    destionationTop
                                )
                            }

                            '{', 'x', 'z' -> {
                                // Erase rectangular area (DECERA - http://www.vt100.net/docs/vt510-rm/DECERA).
                                val erase = 'x'.code != b
                                val selective = '{'.code == b
                                // Only DECSERA keeps visual attributes, DECERA does not:
                                val keepVisualAttributes = erase && selective
                                var argIndex = 0
                                val fillChar = if (erase) {
                                    ' '.code
                                } else {
                                    getArg(argIndex++, -1, true)
                                }
                                // "Pch can be any value from 32 to 126 or from 160 to 255. If Pch is not in this range, then the
                                // terminal ignores the DECFRA command":
                                if ((fillChar in 32..126) || (fillChar in 160..255)) {
                                    // "If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, the value
                                    // is treated as the width or height of that page."
                                    val top = min(
                                        (this.getArg(
                                            argIndex++,
                                            1,
                                            true
                                        ) + effectiveTopMargin),
                                        (effectiveBottomMargin + 1)
                                    )
                                    val left = min(
                                        (this.getArg(
                                            argIndex++,
                                            1,
                                            true
                                        ) + effectiveLeftMargin),
                                        (effectiveRightMargin + 1)
                                    )
                                    val bottom = min(
                                        (this.getArg(
                                            argIndex++,
                                            this.mRows,
                                            true
                                        ) + effectiveTopMargin),
                                        effectiveBottomMargin
                                    )
                                    val right = min(
                                        (this.getArg(
                                            argIndex,
                                            this.mColumns,
                                            true
                                        ) + effectiveLeftMargin),
                                        effectiveRightMargin
                                    )
                                    val style = this.style
                                    var row = top - 1
                                    while (row < bottom) {
                                        var col = left - 1
                                        while (col < right) {
                                            if (!selective || 0 == (decodeEffect(
                                                    screen.getStyleAt(row, col)
                                                ) and CHARACTER_ATTRIBUTE_PROTECTED)
                                            ) screen.setChar(
                                                col,
                                                row,
                                                fillChar,
                                                if (keepVisualAttributes) screen.getStyleAt(
                                                    row,
                                                    col
                                                ) else style
                                            )
                                            col++
                                        }
                                        row++
                                    }
                                }
                            }

                            'r', 't' -> {
                                // Reverse attributes in rectangular area (DECRARA - http://www.vt100.net/docs/vt510-rm/DECRARA).
                                val reverse = 't'.code == b
                                // FIXME: "coordinates of the rectangular area are affected by the setting of origin mode (DECOM)".
                                val top = min(
                                    (this.getArg(0, 1, true) - 1), effectiveBottomMargin
                                ) + effectiveTopMargin
                                val left = min(
                                    (this.getArg(1, 1, true) - 1),
                                    effectiveRightMargin
                                ) + effectiveLeftMargin
                                val bottom = (min(
                                    (this.getArg(2, this.mRows, true) + 1),
                                    (effectiveBottomMargin - 1)
                                ) + effectiveTopMargin)
                                val right = (min(
                                    (this.getArg(3, this.mColumns, true) + 1),
                                    (effectiveRightMargin - 1)
                                ) + effectiveLeftMargin)
                                if (4 <= mArgIndex) {
                                    if (this.mArgIndex >= mArgs.size) this.mArgIndex =
                                        mArgs.size - 1
                                    var i = 4
                                    while (i <= this.mArgIndex) {
                                        var bits = 0
                                        // True if setting, false if clearing.
                                        var setOrClear = true
                                        when (this.getArg(i, 0, false)) {
                                            0 -> {
                                                bits =
                                                    (CHARACTER_ATTRIBUTE_BOLD or CHARACTER_ATTRIBUTE_UNDERLINE or CHARACTER_ATTRIBUTE_BLINK or CHARACTER_ATTRIBUTE_INVERSE)
                                                if (!reverse) setOrClear = false
                                            }

                                            1 -> bits = CHARACTER_ATTRIBUTE_BOLD
                                            4 -> bits = CHARACTER_ATTRIBUTE_UNDERLINE
                                            5 -> bits = CHARACTER_ATTRIBUTE_BLINK
                                            7 -> bits = CHARACTER_ATTRIBUTE_INVERSE
                                            22 -> {
                                                bits = CHARACTER_ATTRIBUTE_BOLD
                                                setOrClear = false
                                            }

                                            24 -> {
                                                bits = CHARACTER_ATTRIBUTE_UNDERLINE
                                                setOrClear = false
                                            }

                                            25 -> {
                                                bits = CHARACTER_ATTRIBUTE_BLINK
                                                setOrClear = false
                                            }

                                            27 -> {
                                                bits = CHARACTER_ATTRIBUTE_INVERSE
                                                setOrClear = false
                                            }
                                        }
                                        if (!reverse || setOrClear) {
                                            screen.setOrClearEffect(
                                                bits,
                                                setOrClear,
                                                reverse,
                                                isDecsetInternalBitSet(
                                                    DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE
                                                ),
                                                effectiveLeftMargin,
                                                effectiveRightMargin,
                                                top,
                                                left,
                                                bottom,
                                                right
                                            )
                                        }
                                        i++
                                    }
                                }
                            }

                            else -> finishSequence()
                        }
                    }

                    ESC_CSI_DOUBLE_QUOTE -> if ('q'.code == b) {
                        // http://www.vt100.net/docs/vt510-rm/DECSCA
                        val arg = getArg0(0)
                        when (arg) {
                            0, 2 -> {
                                // DECSED and DECSEL can erase characters.
                                mEffect = mEffect and CHARACTER_ATTRIBUTE_PROTECTED.inv()
                            }

                            1 -> {
                                // DECSED and DECSEL cannot erase characters.
                                mEffect = mEffect or CHARACTER_ATTRIBUTE_PROTECTED
                            }

                            else -> {
                                finishSequence()
                            }
                        }
                    } else {
                        this.finishSequence()
                    }

                    ESC_CSI_SINGLE_QUOTE -> if ('}'.code == b) {
                        // Insert Ps Column(s) (default = 1) (DECIC), VT420 and up.
                        val columnsAfterCursor = this.mRightMargin - this.mCursorCol
                        val columnsToInsert =
                            min(getArg0(1), columnsAfterCursor)
                        val columnsToMove = columnsAfterCursor - columnsToInsert
                        screen.blockCopy(
                            this.mCursorCol,
                            0,
                            columnsToMove,
                            this.mRows,
                            this.mCursorCol + columnsToInsert,
                            0
                        )
                        this.blockClear(this.mCursorCol, 0, columnsToInsert, this.mRows)
                    } else if ('~'.code == b) {
                        // Delete Ps Column(s) (default = 1) (DECDC), VT420 and up.
                        val columnsAfterCursor = this.mRightMargin - this.mCursorCol
                        val columnsToDelete =
                            min(getArg0(1), columnsAfterCursor)
                        val columnsToMove = columnsAfterCursor - columnsToDelete
                        screen.blockCopy(
                            this.mCursorCol + columnsToDelete,
                            0,
                            columnsToMove,
                            this.mRows,
                            this.mCursorCol,
                            0
                        )
                    } else {
                        this.finishSequence()
                    }

                    9 -> {}
                    ESC_OSC -> this.doOsc(b)
                    ESC_OSC_ESC -> this.doOscEsc(b)
                    ESC_P -> this.doDeviceControl(b)
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> if ('p'.code == b) {
                        // Request DEC private mode (DECRQM).
                        val mode = this.getArg0(0)
                        val value = this.getValues(mode)
                        mSession.write(
                            String.format(
                                Locale.US,
                                "\u001b[?%d;%d\$y",
                                mode,
                                value
                            )
                        )
                    } else {
                        this.finishSequence()
                    }

                    ESC_CSI_ARGS_SPACE -> {
                        val arg = this.getArg0(0)
                        when (b.toChar()) {
                            'q' -> when (arg) {
                                0, 1, 2 -> {
                                    this.cursorStyle = TERMINAL_CURSOR_STYLE_BLOCK
                                    this.mCursorBlinkingEnabled = 2 != arg
                                }

                                3, 4 -> {
                                    this.cursorStyle = TERMINAL_CURSOR_STYLE_UNDERLINE
                                    this.mCursorBlinkingEnabled = 4 != arg
                                }

                                5, 6 -> {
                                    this.cursorStyle = TERMINAL_CURSOR_STYLE_BAR
                                    this.mCursorBlinkingEnabled = 6 != arg
                                }
                            }

                            't', 'u' -> {}
                            else -> this.finishSequence()
                        }
                    }

                    ESC_CSI_ARGS_ASTERIX -> {
                        val attributeChangeExtent = this.getArg0(0)
                        if ('x'.code == b && (attributeChangeExtent in 0..2)) {
                            // Select attribute change extent (DECSACE - http://www.vt100.net/docs/vt510-rm/DECSACE).
                            this.setDecsetinternalBit(
                                DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE,
                                2 == attributeChangeExtent
                            )
                        } else {
                            this.finishSequence()
                        }
                    }

                    else -> this.finishSequence()
                }
                if (!this.mContinueSequence) this.mEscapeState = ESC_NONE
            }
        }
    }

    private fun getValues(mode: Int): Int {
        val value: Int
        if (47 == mode || 1047 == mode || 1049 == mode) {
            // This state is carried by mScreen pointer.
            value = if ((this.screen == this.mAltBuffer)) 1 else 2
        } else {
            val internalBit = mapDecSetBitToInternalBit(mode)
            value = if (-1 != internalBit) {
                // 1=set, 2=reset.
                if (this.isDecsetInternalBitSet(internalBit)) 1 else 2
            } else {
                // 0=not recognized, 3=permanently set, 4=permanently reset
                0
            }
        }
        return value
    }

    /**
     * When in [.ESC_P] ("device control") sequence.
     */
    private fun doDeviceControl(b: Int) {

        if ('\\'.code == b) // ESC \ terminates OSC
        // Sixel sequences may be very long. '$' and '!' are natural for breaking the sequence.
        {
            val dcs = mOSCOrDeviceControlArgs.toString()
            // DCS $ q P t ST. Request Status String (DECRQSS)
            if (dcs.startsWith("\$q")) {
                if ("\$q\"p" == dcs) {
                    // DECSCL, conformance level, http://www.vt100.net/docs/vt510-rm/DECSCL:
                    val csiString = "64;1\"p"
                    mSession.write("\u001bP1\$r$csiString\u001b\\")
                } else {
                    this.finishSequence()
                }
            } else if (dcs.startsWith("+q")) {
                for (part in dcs.substring(2).split(";")) {
                    if (0 == part.length % 2) {
                        val transBuffer = StringBuilder()
                        var c: Char
                        var i = 0
                        while (i < part.length) {
                            try {
                                c = Char(
                                    java.lang.Long.decode("0x" + part[i] + part[i + 1]).toUShort()
                                )
                            } catch (e: NumberFormatException) {
                                i += 2
                                continue
                            }
                            transBuffer.append(c)
                            i += 2
                        }
                        val responseValue = when (val trans = transBuffer.toString()) {
                            "Co", "colors" ->  // Number of colors.
                                "256"

                            "TN", "name" -> "xterm"
                            else -> getCodeFromTermcap(
                                trans, this.isDecsetInternalBitSet(
                                    DECSET_BIT_APPLICATION_CURSOR_KEYS
                                ), this.isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
                            )
                        }
                        if (null == responseValue) {
                            // Respond with invalid request:
                            mSession.write("\u001bP0+r$part\u001b\\")
                        } else {
                            val hexEncoded = StringBuilder()
                            for (element in responseValue) {
                                hexEncoded.append(
                                    String.format(
                                        Locale.US,
                                        "%02X",
                                        element.code
                                    )
                                )
                            }
                            mSession.write("\u001bP1+r$part=$hexEncoded\u001b\\")
                        }
                    }
                }
            }
            this.finishSequence()
        } else {
            if (MAX_OSC_STRING_LENGTH < mOSCOrDeviceControlArgs.length) {
                // Too long.
                mOSCOrDeviceControlArgs.setLength(0)
                this.finishSequence()
            } else {
                mOSCOrDeviceControlArgs.appendCodePoint(b)
                this.continueSequence(this.mEscapeState)
            }
        }
    }

    private fun nextTabStop(numTabs: Int): Int {
        var numTabs1 = numTabs
        for (i in this.mCursorCol + 1 until this.mColumns)
            if (mTabStop[i] && 0 == --numTabs1)
                return min(i, mRightMargin)

        return this.mRightMargin - 1
    }

    /**
     * Process byte while in the [.ESC_CSI_QUESTIONMARK] escape state.
     */
    private fun doCsiQuestionMark(b: Int) {
        when (b.toChar()) {
            'J', 'K' -> {
                this.mAboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1
                var startRow = -1
                var endCol = -1
                var endRow = -1
                val justRow = ('K'.code == b)
                when (this.getArg0(0)) {
                    0 -> {
                        startCol = this.mCursorCol
                        startRow = this.mCursorRow
                        endCol = this.mColumns
                        endRow = if (justRow) (this.mCursorRow + 1) else this.mRows
                    }

                    1 -> {
                        startCol = 0
                        startRow = if (justRow) this.mCursorRow else 0
                        endCol = this.mCursorCol + 1
                        endRow = this.mCursorRow + 1
                    }

                    2 -> {
                        startCol = 0
                        startRow = if (justRow) this.mCursorRow else 0
                        endCol = this.mColumns
                        endRow = if (justRow) (this.mCursorRow + 1) else this.mRows
                    }

                    else -> this.finishSequence()
                }
                val style = this.style
                var row = startRow
                while (row < endRow) {
                    var col = startCol
                    while (col < endCol) {
                        if (0 == (decodeEffect(
                                screen.getStyleAt(
                                    row,
                                    col
                                )
                            ) and CHARACTER_ATTRIBUTE_PROTECTED)
                        )
                            screen.setChar(col, row, fillChar, style)
                        col++
                    }
                    row++
                }
            }

            'h', 'l' -> {
                if (this.mArgIndex >= mArgs.size) this.mArgIndex = mArgs.size - 1
                var i = 0
                while (i <= this.mArgIndex) {
                    this.doDecSetOrReset('h'.code == b, mArgs[i])
                    i++
                }
            }

            'n' -> if (6 == getArg0(-1)) { // Extended Cursor Position (DECXCPR - http://www.vt100.net/docs/vt510-rm/DECXCPR). Page=1.
                mSession.write(
                    String.format(
                        Locale.US,
                        "\u001b[?%d;%d;1R",
                        this.mCursorRow + 1,
                        this.mCursorCol + 1
                    )
                )
            } else {
                this.finishSequence()
            }

            'r', 's' -> {
                if (this.mArgIndex >= mArgs.size) this.mArgIndex =
                    mArgs.size - 1
                var i = 0
                while (i <= this.mArgIndex) {
                    val externalBit = mArgs[i]
                    val internalBit = mapDecSetBitToInternalBit(externalBit)
                    if (-1 != internalBit) {
                        if ('s'.code == b) {
                            this.mSavedDecSetFlags = this.mSavedDecSetFlags or internalBit
                        } else {
                            this.doDecSetOrReset(
                                0 != (mSavedDecSetFlags and internalBit),
                                externalBit
                            )
                        }
                    }
                    i++
                }
            }

            '$' -> {
                this.continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR)
            }

            else -> this.parseArg(b)
        }
    }

    private fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit = mapDecSetBitToInternalBit(externalBit)
        if (-1 != internalBit) {
            this.setDecsetinternalBit(internalBit, setting)
        }
        when (externalBit) {
            1 -> {}
            3 -> {

                this.mTopMargin = 0
                this.mLeftMargin = this.mTopMargin

                this.mBottomMargin = this.mRows
                this.mRightMargin = this.mColumns
                // "DECCOLM resets vertical split console mode (DECLRMM) to unavailable":
                this.setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                // "Erases all data in page memory":
                this.blockClear(0, 0, this.mColumns, this.mRows)
                this.setCursorRowCol(0, 0)
            }

            4 -> {}
            5 -> {}
            6 -> if (setting) this.setCursorPosition(0, 0)
            7, 8, 9, 12, 25, 40, 45, 66 -> {}
            69 -> if (!setting) {
                this.mLeftMargin = 0
                this.mRightMargin = this.mColumns
            }

            1000, 1001, 1002, 1003, 1004, 1005, 1006, 1015, 1034 -> {}
            1048 -> if (setting) this.saveCursor() else this.restoreCursor()

            47, 1047, 1049 -> {
                // Set: Save cursor as in DECSC and use Alternate Console Buffer, clearing it first.
                // Reset: Use Normal Console Buffer and restore cursor as in DECRC.
                val newScreen = if (setting) this.mAltBuffer else this.mMainBuffer
                if (newScreen != this.screen) {
                    val resized =
                        !(newScreen.mColumns == this.mColumns && newScreen.mScreenRows == this.mRows)
                    if (setting) this.saveCursor()
                    this.screen = newScreen
                    if (!setting) {
                        val col = mSavedStateMain.mSavedCursorCol
                        val row = mSavedStateMain.mSavedCursorRow
                        this.restoreCursor()
                        if (resized) {
                            // Restore cursor position _not_ clipped to current console (let resizeScreen() handle that):
                            this.mCursorCol = col
                            this.mCursorRow = row
                        }
                    }
                    // Check if buffer size needs to be updated:
                    if (resized) this.resizeScreen()
                    // Clear new console if alt buffer:
                    if (newScreen == this.mAltBuffer)
                        newScreen.blockSet(0, 0, this.mColumns, this.mRows, ' '.code, style)
                }
            }

            2004 -> {}
            else -> this.finishSequence()
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        when (b.toChar()) {
            'c' -> mSession.write("\u001b[>41;320;0c")
            'm' -> {}
            else -> this.parseArg(b)
        }
    }

    private fun startEscapeSequence() {
        this.mEscapeState = ESC
        this.mArgIndex = 0
        Arrays.fill(mArgs, -1)
    }

    private fun doLinefeed() {
        val belowScrollingRegion = this.mCursorRow >= this.mBottomMargin
        var newCursorRow = this.mCursorRow + 1
        if (belowScrollingRegion) {
            // Move down (but not scroll) as long as we are above the last row.
            if (this.mCursorRow != this.mRows - 1) {
                this.cursorRow = newCursorRow
            }
        } else {
            if (newCursorRow == this.mBottomMargin) {
                this.scrollDownOneLine()
                newCursorRow = this.mBottomMargin - 1
            }
            this.cursorRow = newCursorRow
        }
    }

    private fun continueSequence(state: Int) {
        this.mEscapeState = state
        this.mContinueSequence = true
    }

    private fun doEscPound(b: Int) {
        // Esc # 8 - DEC console alignment test - fill console with E's.
        if ('8'.code == b) {
            screen.blockSet(
                0, 0, this.mColumns, this.mRows, 'E'.code,
                style
            )
        } else {
            this.finishSequence()
        }
    }

    /**
     * Encountering a character in the [.ESC] state.
     */
    private fun doEsc(b: Int) {
        when (b.toChar()) {
            '#' -> this.continueSequence(ESC_POUND)
            '(' -> this.continueSequence(ESC_SELECT_LEFT_PAREN)
            ')' -> this.continueSequence(ESC_SELECT_RIGHT_PAREN)
            '6' -> if (this.mCursorCol > this.mLeftMargin) {
                mCursorCol--
            } else {
                val rows = this.mBottomMargin - this.mTopMargin
                screen.blockCopy(
                    this.mLeftMargin,
                    this.mTopMargin,
                    this.mRightMargin - this.mLeftMargin - 1,
                    rows,
                    this.mLeftMargin + 1,
                    this.mTopMargin
                )
                screen.blockSet(
                    this.mLeftMargin, this.mTopMargin, 1, rows, ' '.code, encode(
                        this.mForeColor, this.mBackColor, 0
                    )
                )
            }

            '7' -> this.saveCursor()
            '8' -> this.restoreCursor()
            '9' -> if (this.mCursorCol < this.mRightMargin - 1) {
                mCursorCol++
            } else {
                val rows = this.mBottomMargin - this.mTopMargin
                screen.blockCopy(
                    this.mLeftMargin + 1,
                    this.mTopMargin,
                    this.mRightMargin - this.mLeftMargin - 1,
                    rows,
                    this.mLeftMargin,
                    this.mTopMargin
                )
                screen.blockSet(
                    this.mRightMargin - 1, this.mTopMargin, 1, rows, ' '.code, encode(
                        this.mForeColor, this.mBackColor, 0
                    )
                )
            }

            'c' -> {
                this.reset()
                mMainBuffer.clearTranscript()
                this.blockClear(0, 0, this.mColumns, this.mRows)
                this.setCursorPosition(0, 0)
            }

            'D' -> this.doLinefeed()
            'E' -> {
                this.cursorCol =
                    if (this.isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) this.mLeftMargin else 0
                this.doLinefeed()
            }

            'F' -> this.setCursorRowCol(0, this.mBottomMargin - 1)
            'H' -> mTabStop[mCursorCol] = true
            'M' ->                 // http://www.vt100.net/docs/vt100-ug/chapter3.html: "Move the active position to the same horizontal
                // position on the preceding line. If the active position is at the top margin, a scroll down is performed".
                if (this.mCursorRow <= this.mTopMargin) {
                    screen.blockCopy(
                        this.mLeftMargin,
                        this.mTopMargin,
                        this.mRightMargin - this.mLeftMargin,
                        this.mBottomMargin - (this.mTopMargin + 1),
                        this.mLeftMargin,
                        this.mTopMargin + 1
                    )
                    this.blockClear(
                        this.mLeftMargin,
                        this.mTopMargin,
                        this.mRightMargin - this.mLeftMargin
                    )
                } else {
                    mCursorRow--
                }

            'N', '0' -> {}
            'P' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                this.continueSequence(ESC_P)
            }

            '[' -> {
                this.continueSequence(ESC_CSI)
                this.mIsCSIStart = true
                this.mLastCSIArg = null
            }

            '=' -> this.setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            ']' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                this.continueSequence(ESC_OSC)
            }

            '>' -> this.setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)

            else -> this.finishSequence()
        }
    }

    /**
     * DECSC save cursor - [...](http://www.vt100.net/docs/vt510-rm/DECSC) . See [.restoreCursor].
     */
    private fun saveCursor() {
        val state =
            if ((this.screen == this.mMainBuffer)) this.mSavedStateMain else this.mSavedStateAlt
        state.mSavedCursorRow = this.mCursorRow
        state.mSavedCursorCol = this.mCursorCol
        state.mSavedEffect = this.mEffect
        state.mSavedForeColor = this.mForeColor
        state.mSavedBackColor = this.mBackColor
        state.mSavedDecFlags = this.mCurrentDecSetFlags
        state.mUseLineDrawingG0 = this.mUseLineDrawingG0
        state.mUseLineDrawingG1 = this.mUseLineDrawingG1
        state.mUseLineDrawingUsesG0 = this.mUseLineDrawingUsesG0
    }

    /**
     * DECRS restore cursor - [...](http://www.vt100.net/docs/vt510-rm/DECRC). See [.saveCursor].
     */
    private fun restoreCursor() {
        val state =
            if ((this.screen == this.mMainBuffer)) this.mSavedStateMain else this.mSavedStateAlt
        this.setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol)
        this.mEffect = state.mSavedEffect
        this.mForeColor = state.mSavedForeColor
        this.mBackColor = state.mSavedBackColor
        val mask = (DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE)
        this.mCurrentDecSetFlags =
            (this.mCurrentDecSetFlags and mask.inv()) or (state.mSavedDecFlags and mask)
        this.mUseLineDrawingG0 = state.mUseLineDrawingG0
        this.mUseLineDrawingG1 = state.mUseLineDrawingG1
        this.mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0
    }

    /**
     * Following a CSI - Control Sequence Introducer, "\033[". [.ESC_CSI].
     */
    private fun doCsi(b: Int) {
        when (b.toChar()) {
            '!' -> this.continueSequence(ESC_CSI_EXCLAMATION)
            '"' -> this.continueSequence(ESC_CSI_DOUBLE_QUOTE)
            '\'' -> this.continueSequence(ESC_CSI_SINGLE_QUOTE)
            '$' -> this.continueSequence(ESC_CSI_DOLLAR)
            '*' -> this.continueSequence(ESC_CSI_ARGS_ASTERIX)
            '@' -> {
                // "CSI{n}@" - Insert ${n} space characters (ICH) - http://www.vt100.net/docs/vt510-rm/ICH.
                this.mAboutToAutoWrap = false
                val columnsAfterCursor = this.mColumns - this.mCursorCol
                val spacesToInsert = min(getArg0(1), columnsAfterCursor)
                val charsToMove = columnsAfterCursor - spacesToInsert
                screen.blockCopy(
                    this.mCursorCol,
                    this.mCursorRow,
                    charsToMove,
                    1,
                    this.mCursorCol + spacesToInsert,
                    this.mCursorRow
                )
                this.blockClear(this.mCursorCol, this.mCursorRow, spacesToInsert)
            }

            'A' -> this.cursorRow = max(0, (this.mCursorRow - this.getArg0(1)))

            'B' -> this.cursorRow =
                min((this.mRows - 1), (this.mCursorRow + this.getArg0(1)))

            'C', 'a' -> this.cursorCol = min(
                (this.mRightMargin - 1),
                (this.mCursorCol + this.getArg0(1))
            )

            'D' -> this.cursorCol =
                max(mLeftMargin, (this.mCursorCol - this.getArg0(1)))

            'E' -> this.setCursorPosition(0, this.mCursorRow + this.getArg0(1))
            'F' -> this.setCursorPosition(0, this.mCursorRow - this.getArg0(1))
            'G' -> this.cursorCol = min(max(1, getArg0(1)), mColumns) - 1

            'H', 'f' -> this.setCursorPosition(this.getArg1(1) - 1, this.getArg0(1) - 1)
            'I' -> this.cursorCol = this.nextTabStop(this.getArg0(1))
            'J' -> {
                when (this.getArg0(0)) {
                    0 -> {
                        this.blockClear(
                            this.mCursorCol,
                            this.mCursorRow,
                            this.mColumns - this.mCursorCol
                        )
                        this.blockClear(
                            0,
                            this.mCursorRow + 1,
                            this.mColumns,
                            this.mRows - (this.mCursorRow + 1)
                        )
                    }

                    1 -> {
                        this.blockClear(0, 0, this.mColumns, this.mCursorRow)
                        this.blockClear(0, this.mCursorRow, this.mCursorCol + 1)
                    }

                    2 -> this.blockClear(0, 0, this.mColumns, this.mRows)

                    3 -> mMainBuffer.clearTranscript()

                    else -> {
                        this.finishSequence()
                        return
                    }
                }
                this.mAboutToAutoWrap = false
            }

            'K' -> {
                when (this.getArg0(0)) {
                    0 -> this.blockClear(
                        this.mCursorCol,
                        this.mCursorRow,
                        this.mColumns - this.mCursorCol
                    )

                    1 -> this.blockClear(0, this.mCursorRow, this.mCursorCol + 1)
                    2 -> this.blockClear(0, this.mCursorRow, this.mColumns)

                    else -> {
                        this.finishSequence()
                        return
                    }
                }
                this.mAboutToAutoWrap = false
            }

            'L' -> {
                val linesAfterCursor = this.mBottomMargin - this.mCursorRow
                val linesToInsert = min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToInsert
                screen.blockCopy(
                    0,
                    this.mCursorRow,
                    this.mColumns,
                    linesToMove,
                    0,
                    this.mCursorRow + linesToInsert
                )
                this.blockClear(0, this.mCursorRow, this.mColumns, linesToInsert)
            }

            'M' -> {
                this.mAboutToAutoWrap = false
                val linesAfterCursor = this.mBottomMargin - this.mCursorRow
                val linesToDelete = min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToDelete
                screen.blockCopy(
                    0,
                    this.mCursorRow + linesToDelete,
                    this.mColumns,
                    linesToMove,
                    0,
                    this.mCursorRow
                )
                this.blockClear(0, this.mCursorRow + linesToMove, this.mColumns, linesToDelete)
            }

            'P' -> {
                this.mAboutToAutoWrap = false
                val cellsAfterCursor = this.mColumns - this.mCursorCol
                val cellsToDelete = min(getArg0(1), cellsAfterCursor)
                val cellsToMove = cellsAfterCursor - cellsToDelete
                screen.blockCopy(
                    this.mCursorCol + cellsToDelete,
                    this.mCursorRow,
                    cellsToMove,
                    1,
                    this.mCursorCol,
                    this.mCursorRow
                )
                this.blockClear(this.mCursorCol + cellsToMove, this.mCursorRow, cellsToDelete)
            }

            'S' -> {
                // "${CSI}${N}S" - scroll up ${N} lines (default = 1) (SU).
                val linesToScroll = this.getArg0(1)
                var i = 0
                while (i < linesToScroll) {
                    this.scrollDownOneLine()
                    i++
                }
            }

            'T' -> if (0 == mArgIndex) {
                val linesToScrollArg = this.getArg0(1)
                val linesBetweenTopAndBottomMargins = this.mBottomMargin - this.mTopMargin
                val linesToScroll =
                    min(linesBetweenTopAndBottomMargins, linesToScrollArg)
                screen.blockCopy(
                    this.mLeftMargin,
                    this.mTopMargin,
                    this.mRightMargin - this.mLeftMargin,
                    linesBetweenTopAndBottomMargins - linesToScroll,
                    this.mLeftMargin,
                    this.mTopMargin + linesToScroll
                )
                this.blockClear(
                    this.mLeftMargin,
                    this.mTopMargin,
                    this.mRightMargin - this.mLeftMargin,
                    linesToScroll
                )
            } else {
                // "${CSI}${func};${startx};${starty};${firstrow};${lastrow}T" - initiate highlight mouse tracking.
                this.finishSequence()
            }

            'X' -> {
                this.mAboutToAutoWrap = false
                screen.blockSet(
                    this.mCursorCol, this.mCursorRow, min(
                        getArg0(1), (this.mColumns - this.mCursorCol)
                    ), 1, ' '.code,
                    style
                )
            }

            'Z' -> {
                var numberOfTabs = this.getArg0(1)
                var newCol = this.mLeftMargin
                var i = this.mCursorCol - 1
                while (0 <= i) {
                    if (mTabStop[i]) {
                        --numberOfTabs
                        if (0 == numberOfTabs) {
                            newCol = max(i, mLeftMargin)
                            break
                        }
                    }
                    i--
                }
                this.mCursorCol = newCol
            }

            '?' -> this.continueSequence(ESC_CSI_QUESTIONMARK)
            '>' -> this.continueSequence(ESC_CSI_BIGGERTHAN)
            '`' -> this.setCursorColRespectingOriginMode(this.getArg0(1) - 1)
            'b' -> {
                if (-1 == mLastEmittedCodePoint) return
                val numRepeat = this.getArg0(1)
                var i = 0
                while (i < numRepeat) {
                    this.emitCodePoint(this.mLastEmittedCodePoint)
                    i++
                }
            }

            'c' -> if (0 == getArg0(0)) mSession.write("\u001b[?64;1;2;6;9;15;18;21;22c")

            'd' -> this.cursorRow = min(max(1, getArg0(1)), mRows) - 1

            'e' -> this.setCursorPosition(this.mCursorCol, this.mCursorRow + this.getArg0(1))
            'g' -> when (this.getArg0(0)) {
                0 -> mTabStop[mCursorCol] = false
                3 -> {
                    var i = 0
                    while (i < this.mColumns) {
                        mTabStop[i] = false
                        i++
                    }
                }
            }

            'h' -> this.doSetMode(true)
            'l' -> this.doSetMode(false)
            'm' -> this.selectGraphicRendition()
            'n' -> when (this.getArg0(0)) {
                5 -> {
                    // Answer is ESC [ 0 n (Terminal OK).
                    val dsr = byteArrayOf(
                        27.toByte(),
                        '['.code.toByte(),
                        '0'.code.toByte(),
                        'n'.code.toByte()
                    )
                    mSession.write(dsr, 0, dsr.size)
                }

                6 ->                         // Answer is ESC [ y ; x R, where x,y is
                    // the cursor location.
                    mSession.write(
                        String.format(
                            Locale.US,

                            "\u001b[%d;%dR",
                            this.mCursorRow + 1,
                            this.mCursorCol + 1
                        )
                    )
            }

            'r' -> {
                this.mTopMargin =
                    max(0, min((this.getArg0(1) - 1), (this.mRows - 2)))
                this.mBottomMargin = max(
                    (this.mTopMargin + 2), min(
                        getArg1(this.mRows),
                        mRows
                    )
                )
                // DECSTBM moves the cursor to column 1, line 1 of the page respecting origin mode.
                this.setCursorPosition(0, 0)
            }

            's' -> if (this.isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                // Set left and right margins (DECSLRM - http://www.vt100.net/docs/vt510-rm/DECSLRM).
                this.mLeftMargin =
                    min((this.getArg0(1) - 1), (this.mColumns - 2))
                this.mRightMargin = max(
                    (this.mLeftMargin + 1), min(
                        getArg1(this.mColumns),
                        mColumns
                    )
                )
                // DECSLRM moves the cursor to column 1, line 1 of the page.
                this.setCursorPosition(0, 0)
            } else {
                // Save cursor (ANSI.SYS), available only when DECLRMM is disabled.
                this.saveCursor()
            }

            't' -> when (this.getArg0(0)) {
                11 -> mSession.write("\u001b[1t")
                13 -> mSession.write("\u001b[3;0;0t")
                14 -> mSession.write(
                    String.format(
                        Locale.US,

                        "\u001b[4;%d;%dt",
                        this.mRows * 12,
                        this.mColumns * 12
                    )
                )

                18 -> mSession.write(
                    String.format(
                        Locale.US,

                        "\u001b[8;%d;%dt",
                        this.mRows,
                        this.mColumns
                    )
                )

                19 ->                         // We report the same size as the view, since it's the view really isn't resizable from the shell.
                    mSession.write(
                        String.format(
                            Locale.US,

                            "\u001b[9;%d;%dt",
                            this.mRows,
                            this.mColumns
                        )
                    )

                20 -> mSession.write("\u001b]LIconLabel\u001b\\")
                21 -> mSession.write("\u001b]l\u001b\\")
            }

            'u' -> this.restoreCursor()
            ' ' -> this.continueSequence(ESC_CSI_ARGS_SPACE)
            else -> this.parseArg(b)
        }
    }

    /**
     * Select Graphic Rendition (SGR) - see [...](http://en.wikipedia.org/wiki/ANSI_escape_code#graphics).
     */
    private fun selectGraphicRendition() {
        if (this.mArgIndex >= mArgs.size) this.mArgIndex =
            mArgs.size - 1
        var i = 0
        while (i <= this.mArgIndex) {
            var code = mArgs[i]
            if (0 > code) {
                if (0 < mArgIndex) {
                    i++
                    continue
                } else {
                    code = 0
                }
            }
            if (0 == code) {
                // reset
                this.mForeColor = COLOR_INDEX_FOREGROUND
                this.mBackColor = COLOR_INDEX_BACKGROUND
                this.mEffect = 0
            } else if (1 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_BOLD
            } else if (2 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_DIM
            } else if (3 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_ITALIC
            } else if (4 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_UNDERLINE
            } else if (5 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_BLINK
            } else if (7 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_INVERSE
            } else if (8 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_INVISIBLE
            } else if (9 == code) {
                this.mEffect = this.mEffect or CHARACTER_ATTRIBUTE_STRIKETHROUGH
            } else if (22 == code) {
                // Normal color or intensity, neither bright, bold nor faint.
                this.mEffect =
                    this.mEffect and (CHARACTER_ATTRIBUTE_BOLD or CHARACTER_ATTRIBUTE_DIM).inv()
            } else if (23 == code) {
                // not italic, but rarely used as such; clears standout with TERM=console
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_ITALIC.inv()
            } else if (24 == code) {
                // underline: none
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_UNDERLINE.inv()
            } else if (25 == code) {
                // blink: none
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_BLINK.inv()
            } else if (27 == code) {
                // image: positive
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_INVERSE.inv()
            } else if (28 == code) {
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_INVISIBLE.inv()
            } else if (29 == code) {
                this.mEffect = this.mEffect and CHARACTER_ATTRIBUTE_STRIKETHROUGH.inv()
            } else if (code in 30..37) {
                this.mForeColor = code - 30
            } else if (38 == code || 48 == code) {
                if (i + 2 > this.mArgIndex) {
                    i++
                    continue
                }
                val firstArg = mArgs[i + 1]
                if (2 == firstArg) {
                    if (i + 4 <= this.mArgIndex) {
                        val red = mArgs[i + 2]
                        val green = mArgs[i + 3]
                        val blue = mArgs[i + 4]
                        if ((red !in 0..255) or (green !in 0..255) or (blue !in 0..255)) {
                            this.finishSequence()
                        } else {
                            val argbColor = -0x1000000 or (red shl 16) or (green shl 8) or blue
                            if (38 == code) {
                                this.mForeColor = argbColor
                            } else {
                                this.mBackColor = argbColor
                            }
                        }
                        // "2;P_r;P_g;P_r"
                        i += 4
                    }
                } else if (5 == firstArg) {
                    val color = mArgs[i + 2]
                    // "5;P_s"
                    i += 2
                    if (color in 0..<NUM_INDEXED_COLORS) {
                        if (38 == code) {
                            this.mForeColor = color
                        } else {
                            this.mBackColor = color
                        }
                    }
                } else {
                    this.finishSequence()
                }
            } else if (39 == code) {
                // Set default foreground color.
                this.mForeColor = COLOR_INDEX_FOREGROUND
            } else if (code in 40..47) {
                // Set background color.
                this.mBackColor = code - 40
            } else if (49 == code) {
                // Set default background color.
                this.mBackColor = COLOR_INDEX_BACKGROUND
            } else if (code in 90..97) {
                // Bright foreground colors (aixterm codes).
                this.mForeColor = code - 90 + 8
            } else if (code in 100..107) {
                // Bright background color (aixterm codes).
                this.mBackColor = code - 100 + 8
            }
            i++
        }
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> this.doOscSetTextParameters("\u0007")
            27 -> this.continueSequence(ESC_OSC_ESC)
            else -> this.collectOSCArgs(b)
        }
    }

    private fun doOscEsc(b: Int) {
        if ('\\'.code == b) {
            this.doOscSetTextParameters("\u001b\\")
        } else { // The ESC character was not followed by a \, so insert the ESC and
            // the current character in arg buffer.
            this.collectOSCArgs(27)
            this.collectOSCArgs(b)
            this.continueSequence(ESC_OSC)
        }
    }

    /**
     * An Operating System Controls (OSC) Set Text Parameters. May come here from BEL or ST.
     */
    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        var value = -1
        var textParameter = ""
        // Extract initial $value from initial "$value;..." string.
        for (mOSCArgTokenizerIndex in mOSCOrDeviceControlArgs.indices) {
            val b = mOSCOrDeviceControlArgs[mOSCArgTokenizerIndex]
            if (';' == b) {
                textParameter = mOSCOrDeviceControlArgs.substring(mOSCArgTokenizerIndex + 1)
                break
            } else if (b in '0'..'9') {
                value = (if (0 > value) 0 else value * 10) + (b.code - '0'.code)
            } else {
                this.finishSequence()
                return
            }
        }
        when (value) {
            0, 1, 2 -> {/*we used to set window title [textParameter]*/
            }

            4 -> {
                var colorIndex = -1
                var parsingPairStart = -1
                var i = 0
                while (true) {
                    val endOfInput = i == textParameter.length
                    val b = if (endOfInput) ';' else textParameter[i]
                    if (';' == b) {
                        if (0 > parsingPairStart) {
                            parsingPairStart = i + 1
                        } else {
                            if (0 > colorIndex || 255 < colorIndex) {
                                this.finishSequence()
                                return
                            } else {
                                mColors.tryParseColor(
                                    colorIndex,
                                    textParameter.substring(parsingPairStart, i)
                                )
                                colorIndex = -1
                                parsingPairStart = -1
                            }
                        }
                    } else if (parsingPairStart >= 0) {
                        // We have passed a color index and are now going through color spec.
                    } else if (/*0 > parsingPairStart && */(b in '0'..'9')) {
                        colorIndex =
                            (if ((0 > colorIndex)) 0 else colorIndex * 10) + (b.code - '0'.code)
                    } else {
                        this.finishSequence()
                        return
                    }
                    if (endOfInput) break
                    i++
                }
            }

            10, 11, 12 -> {
                var specialIndex = COLOR_INDEX_FOREGROUND + (value - 10)
                var lastSemiIndex = 0
                var charIndex = 0
                while (true) {
                    val endOfInput = charIndex == textParameter.length
                    if (endOfInput || ';' == textParameter[charIndex]) {
                        try {
                            val colorSpec = textParameter.substring(lastSemiIndex, charIndex)
                            if ("?" == colorSpec) {
                                // Report current color in the same format xterm and gnome-terminal does.
                                val rgb = mColors.mCurrentColors[specialIndex]
                                val r = (65535 * ((rgb and 0x00FF0000) shr 16)) / 255
                                val g = (65535 * ((rgb and 0x0000FF00) shr 8)) / 255
                                val b = (65535 * (rgb and 0x000000FF)) / 255
                                mSession.write(
                                    "\u001b]$value;rgb:" + String.format(
                                        Locale.US,

                                        "%04x",
                                        r
                                    ) + "/" + String.format(
                                        Locale.US,
                                        "%04x", g
                                    ) + "/" + String.format(
                                        Locale.US,

                                        "%04x",
                                        b
                                    ) + bellOrStringTerminator
                                )
                            } else {
                                mColors.tryParseColor(specialIndex, colorSpec)
                            }
                            specialIndex++
                            if (endOfInput || (COLOR_INDEX_CURSOR < specialIndex) || ++charIndex >= textParameter.length) break
                            lastSemiIndex = charIndex
                        } catch (e: NumberFormatException) {
                            // Ignore.
                        }
                    }
                    charIndex++
                }
            }

            52 -> {
                val startIndex = textParameter.indexOf(';') + 1
                try {
                    val clipboardText = String(
                        Base64.decode(textParameter.substring(startIndex), 0),
                        Charsets.UTF_8
                    )
                    mSession.onCopyTextToClipboard(clipboardText)
                } catch (ignored: Exception) {
                }
            }

            104 -> if (textParameter.isEmpty()) {
                mColors.reset()
            } else {
                var lastIndex = 0
                var charIndex = 0
                while (true) {
                    val endOfInput = charIndex == textParameter.length
                    if (endOfInput || ';' == textParameter[charIndex]) {
                        try {
                            val colorToReset =
                                textParameter.substring(lastIndex, charIndex).toInt()
                            mColors.reset(colorToReset)
                            if (endOfInput) break
                            charIndex++
                            lastIndex = charIndex
                        } catch (e: NumberFormatException) {
                            // Ignore.
                        }
                    }
                    charIndex++
                }
            }

            110, 111, 112 -> mColors.reset(COLOR_INDEX_FOREGROUND + (value - 110))
            119 -> {}
            else -> this.finishSequence()
        }
        finishSequence()
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int = 1) {
        screen.blockSet(sx, sy, w, h, ' '.code, this.style)
    }

    private val style: Long
        get() = encode(this.mForeColor, this.mBackColor, this.mEffect)

    /**
     * "CSI P_m h" for set or "CSI P_m l" for reset ANSI mode.
     */
    private fun doSetMode(newValue: Boolean) {
        val modeBit = this.getArg0(0)
        when (modeBit) {
            4 -> this.mInsertMode = newValue
            34 -> {}
            else -> this.finishSequence()
        }
    }

    /**
     * NOTE: The parameters of this function respect the [.DECSET_BIT_ORIGIN_MODE]. Use
     * [.setCursorRowCol] for absolute pos.
     */
    private fun setCursorPosition(x: Int, y: Int) {
        val originMode = this.isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
        val effectiveTopMargin = if (originMode) this.mTopMargin else 0
        val effectiveBottomMargin = if (originMode) this.mBottomMargin else this.mRows
        val effectiveLeftMargin = if (originMode) this.mLeftMargin else 0
        val effectiveRightMargin = if (originMode) this.mRightMargin else this.mColumns
        val newRow = max(
            effectiveTopMargin,
            min((effectiveTopMargin + y), (effectiveBottomMargin - 1))
        )
        val newCol = max(
            effectiveLeftMargin,
            min((effectiveLeftMargin + x), (effectiveRightMargin - 1))
        )
        this.setCursorRowCol(newRow, newCol)
    }

    private fun scrollDownOneLine() {
        scrollCounter++
        if (0 != mLeftMargin || this.mRightMargin != this.mColumns) {
            // Horizontal margin: Do not put anything into scroll history, just non-margin part of console up.
            screen.blockCopy(
                this.mLeftMargin,
                this.mTopMargin + 1,
                this.mRightMargin - this.mLeftMargin,
                this.mBottomMargin - this.mTopMargin - 1,
                this.mLeftMargin,
                this.mTopMargin
            )
            // .. and blank bottom row between margins:
            screen.blockSet(
                this.mLeftMargin,
                this.mBottomMargin - 1,
                this.mRightMargin - this.mLeftMargin,
                1,
                ' '.code,
                mEffect.toLong()
            )
        } else {
            screen.scrollDownOneLine(
                this.mTopMargin, this.mBottomMargin,
                style
            )
        }
    }

    /**
     * Process the next ASCII character of a parameter.
     *
     *
     * Parameter characters modify the action or interpretation of the sequence. You can use up to
     * 16 parameters per sequence. You must use the ; character to separate parameters.
     * All parameters are unsigned, positive decimal integers, with the most significant
     * digit sent first. Any parameter greater than 9999 (decimal) is set to 9999
     * (decimal). If you do not specify a value, a 0 value is assumed. A 0 value
     * or omitted parameter indicates a default value for the sequence. For most
     * sequences, the default value is 1.
     *
     * [* https://vt100.net/docs/vt510-rm/chapter4.htm](
      )l#S4.3.3
     */
    private fun parseArg(inputByte: Int) {
        val bytes = this.getInts(inputByte)
        this.mIsCSIStart = false
        for (b in bytes) {
            if (b in '0'.code..'9'.code) {
                if (this.mArgIndex < mArgs.size) {
                    val oldValue = mArgs[mArgIndex]
                    val thisDigit = b - '0'.code
                    var value: Int
                    value = if (0 <= oldValue) {
                        oldValue * 10 + thisDigit
                    } else {
                        thisDigit
                    }
                    if (9999 < value) value = 9999
                    mArgs[mArgIndex] = value
                }
                this.continueSequence(this.mEscapeState)
            } else if (';'.code == b) {
                if (this.mArgIndex < mArgs.size) {
                    mArgIndex++
                }
                this.continueSequence(this.mEscapeState)
            } else {
                this.finishSequence()
            }
            this.mLastCSIArg = b
        }
    }

    private fun getInts(inputByte: Int): IntArray {
        var bytes = intArrayOf(inputByte)
        // Only doing this for ESC_CSI and not for other ESC_CSI_* since they seem to be using their
        // own defaults with getArg*() calls, but there may be missed cases
        if (ESC_CSI == mEscapeState) {
            if ( // If sequence starts with a ; character, like \033[;m
                (this.mIsCSIStart && ';'.code == inputByte) || (!this.mIsCSIStart && null != mLastCSIArg && ';'.code == mLastCSIArg && ';'.code == inputByte)) {
                // If sequence contains sequential ; characters, like \033[;;m
                // Assume 0 was passed
                bytes = intArrayOf('0'.code, ';'.code)
            }
        }
        return bytes
    }

    private fun getArg0(defaultValue: Int): Int {
        return this.getArg(0, defaultValue, true)
    }

    private fun getArg1(defaultValue: Int): Int {
        return this.getArg(1, defaultValue, true)
    }

    private fun getArg(index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
        var result = mArgs[index]
        if (0 > result || (0 == result && treatZeroAsDefault)) {
            result = defaultValue
        }
        return result
    }

    private fun collectOSCArgs(b: Int) {
        if (MAX_OSC_STRING_LENGTH > mOSCOrDeviceControlArgs.length) {
            mOSCOrDeviceControlArgs.appendCodePoint(b)
            this.continueSequence(this.mEscapeState)
        } else {
            this.finishSequence()
        }
    }

    private fun finishSequence() {
        this.mEscapeState = ESC_NONE
    }

    /**
     * Send a Unicode code point to the console.
     *
     * @param codePoint The code point of the character to display
     */
    private fun emitCodePoint(codePoint: Int) {
        var codePoint1 = codePoint
        this.mLastEmittedCodePoint = codePoint
        if (if (this.mUseLineDrawingUsesG0) this.mUseLineDrawingG0 else this.mUseLineDrawingG1) {
            // http://www.vt100.net/docs/vt102-ug/table5-15.html.
            when (codePoint1.toChar()) {
                '_' ->                     // Blank.
                    codePoint1 = ' '.code

                '`' ->                     // Diamond.
                    codePoint1 = '◆'.code

                '0' ->                     // Solid block;
                    codePoint1 = '█'.code

                'a' ->                     // Checker board.
                    codePoint1 = '▒'.code

                'b' ->                     // Horizontal tab.
                    codePoint1 = '␉'.code

                'c' ->                     // Form feed.
                    codePoint1 = '␌'.code

                'd' ->                     // Carriage return.
                    codePoint1 = '\r'.code

                'e' ->                     // Linefeed.
                    codePoint1 = '␊'.code

                'f' ->                     // Degree.
                    codePoint1 = '°'.code

                'g' ->                     // Plus-minus.
                    codePoint1 = '±'.code

                'h' ->                     // Newline.
                    codePoint1 = '\n'.code

                'i' ->                     // Vertical tab.
                    codePoint1 = '␋'.code

                'j' ->                     // Lower right corner.
                    codePoint1 = '┘'.code

                'k' ->                     // Upper right corner.
                    codePoint1 = '┐'.code

                'l' ->                     // Upper left corner.
                    codePoint1 = '┌'.code

                'm' ->                     // Left left corner.
                    codePoint1 = '└'.code

                'n' ->                     // Crossing lines.
                    codePoint1 = '┼'.code

                'o' ->                     // Horizontal line - scan 1.
                    codePoint1 = '⎺'.code

                'p' ->                     // Horizontal line - scan 3.
                    codePoint1 = '⎻'.code

                'q' ->                     // Horizontal line - scan 5.
                    codePoint1 = '─'.code

                'r' ->                     // Horizontal line - scan 7.
                    codePoint1 = '⎼'.code

                's' ->                     // Horizontal line - scan 9.
                    codePoint1 = '⎽'.code

                't' ->                     // T facing rightwards.
                    codePoint1 = '├'.code

                'u' ->                     // T facing leftwards.
                    codePoint1 = '┤'.code

                'v' ->                     // T facing upwards.
                    codePoint1 = '┴'.code

                'w' ->                     // T facing downwards.
                    codePoint1 = '┬'.code

                'x' ->                     // Vertical line.
                    codePoint1 = '│'.code

                'y' ->                     // Less than or equal to.
                    codePoint1 = '≤'.code

                'z' ->                     // Greater than or equal to.
                    codePoint1 = '≥'.code

                '{' ->                     // Pi.
                    codePoint1 = 'π'.code

                '|' ->                     // Not equal to.
                    codePoint1 = '≠'.code

                '}' ->                     // UK pound.
                    codePoint1 = '£'.code

                '~' ->                     // Centered dot.
                    codePoint1 = '·'.code
            }
        }
        val autoWrap = this.isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(codePoint1)
        val cursorInLastColumn = this.mCursorCol == this.mRightMargin - 1
        if (autoWrap) {
            if (cursorInLastColumn && ((this.mAboutToAutoWrap && 1 == displayWidth) || 2 == displayWidth)) {
                screen.setLineWrap(this.mCursorRow)
                this.mCursorCol = this.mLeftMargin
                if (this.mCursorRow + 1 < this.mBottomMargin) {
                    mCursorRow++
                } else {
                    this.scrollDownOneLine()
                }
            }
        } else if (cursorInLastColumn && 2 == displayWidth) {
            // The behaviour when a wide character is output with cursor in the last column when
            // autowrap is disabled is not obvious - it's ignored here.
            return
        }
        if (this.mInsertMode && 0 < displayWidth) {
            // Move character to right one space.
            val destCol = this.mCursorCol + displayWidth
            if (destCol < this.mRightMargin) screen.blockCopy(
                this.mCursorCol,
                this.mCursorRow,
                this.mRightMargin - destCol,
                1,
                destCol,
                this.mCursorRow
            )
        }
        val column = this.getColumn(displayWidth)
        screen.setChar(
            column, this.mCursorRow, codePoint1,
            style
        )
        if (autoWrap && 0 < displayWidth) this.mAboutToAutoWrap =
            (this.mCursorCol == this.mRightMargin - displayWidth)
        this.mCursorCol =
            min((this.mCursorCol + displayWidth), (this.mRightMargin - 1))
    }

    private fun getColumn(displayWidth: Int): Int {
        val offsetDueToCombiningChar =
            (if (0 >= displayWidth && 0 < mCursorCol && !this.mAboutToAutoWrap) 1 else 0)
        var column = this.mCursorCol - offsetDueToCombiningChar
        // Fix TerminalRow.setChar() ArrayIndexOutOfBoundsException index=-1 exception reported
        // The offsetDueToCombiningChar would never be 1 if mCursorCol was 0 to get column/index=-1,
        // so was mCursorCol changed after the offsetDueToCombiningChar conditional by another thread?
        // TODO: Check if there are thread synchronization issues with mCursorCol and mCursorRow, possibly causing others bugs too.
        if (0 > column) column = 0
        return column
    }

    /**
     * Set the cursor mode, but limit it to margins if [.DECSET_BIT_ORIGIN_MODE] is enabled.
     */
    private fun setCursorColRespectingOriginMode(col: Int) {
        this.setCursorPosition(col, this.mCursorRow)
    }

    /**
     * TODO: Better name, distinguished from [.setCursorPosition] by not regarding origin mode.
     */
    private fun setCursorRowCol(row: Int, col: Int) {
        this.mCursorRow = max(0, min(row, (this.mRows - 1)))
        this.mCursorCol = max(
            0,
            min(col, (this.mColumns - 1))
        )
        this.mAboutToAutoWrap = false
    }

    fun clearScrollCounter() {
        this.scrollCounter = 0
    }


    /**
     * Reset terminal state so user can interact with it regardless of present state.
     */
    private fun reset() {
        this.mArgIndex = 0
        this.mContinueSequence = false
        this.mEscapeState = ESC_NONE
        this.mInsertMode = false
        this.mLeftMargin = 0
        this.mTopMargin = this.mLeftMargin
        this.mBottomMargin = this.mRows
        this.mRightMargin = this.mColumns
        this.mAboutToAutoWrap = false
        mSavedStateAlt.mSavedForeColor = COLOR_INDEX_FOREGROUND
        mSavedStateMain.mSavedForeColor = mSavedStateAlt.mSavedForeColor
        this.mForeColor = mSavedStateMain.mSavedForeColor
        mSavedStateAlt.mSavedBackColor = COLOR_INDEX_BACKGROUND
        mSavedStateMain.mSavedBackColor = mSavedStateAlt.mSavedBackColor
        this.mBackColor = mSavedStateMain.mSavedBackColor
        this.setDefaultTabStops()
        this.mUseLineDrawingG1 = false
        this.mUseLineDrawingG0 = this.mUseLineDrawingG1
        this.mUseLineDrawingUsesG0 = true
        mSavedStateMain.mSavedEffect = 0
        mSavedStateMain.mSavedCursorCol = 0
        mSavedStateMain.mSavedCursorRow = 0
        mSavedStateAlt.mSavedEffect = 0
        mSavedStateAlt.mSavedCursorCol = 0
        mSavedStateAlt.mSavedCursorRow = 0
        this.mCurrentDecSetFlags = 0
        // Initial wrap-around is not accurate but makes terminal more useful, especially on a small console:
        this.setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true)
        this.setDecsetinternalBit(DECSET_BIT_CURSOR_ENABLED, true)
        mSavedStateAlt.mSavedDecFlags = 0
        mSavedStateMain.mSavedDecFlags = 0
        this.mSavedDecSetFlags = 0
        // XXX: Should we set terminal driver back to IUTF8 with termios?
        this.mUtf8ToFollow = 0
        this.mUtf8Index = 0
        mColors.reset()
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String {
        return screen.getSelectedText(x1, y1, x2, y2)
    }

    /**
     * If DECSET 2004 is set, prefix paste with "\033[200~" and suffix with "\033[201~".
     */
    fun paste(text: CharSequence) {
        // First: Always remove escape key and C1 control characters [0x80,0x9F]:
        var text1 = text
        text1 = REGEX.matcher(text1).replaceAll("")
        // Second: Replace all newlines (\n) or CRLF (\r\n) with carriage returns (\r).
        text1 = PATTERN.matcher(text1).replaceAll("\r")
        // Then: Implement bracketed paste mode if enabled:
        val bracketed = this.isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
        if (bracketed) mSession.write("\u001b[200~")
        mSession.write(text1.toString())
        if (bracketed) mSession.write("\u001b[201~")
    }

    /**
     * [...](http://www.vt100.net/docs/vt510-rm/DECSC)
     */
    internal class SavedScreenState {
        /**
         * Saved state of the cursor position, Used to implement the save/restore cursor position escape sequences.
         */
        var mSavedCursorRow: Int = 0
        var mSavedCursorCol: Int = 0

        var mSavedEffect: Int = 0
        var mSavedForeColor: Int = 0
        var mSavedBackColor: Int = 0

        var mSavedDecFlags: Int = 0

        var mUseLineDrawingG0: Boolean = false
        var mUseLineDrawingG1: Boolean = false
        var mUseLineDrawingUsesG0: Boolean = true
    }

    private fun mapDecSetBitToInternalBit(decsetBit: Int): Int {
        return when (decsetBit) {
            1 -> DECSET_BIT_APPLICATION_CURSOR_KEYS
            5 -> DECSET_BIT_REVERSE_VIDEO
            6 -> DECSET_BIT_ORIGIN_MODE
            7 -> DECSET_BIT_AUTOWRAP
            25 -> DECSET_BIT_CURSOR_ENABLED
            66 -> DECSET_BIT_APPLICATION_KEYPAD
            69 -> DECSET_BIT_LEFTRIGHT_MARGIN_MODE
            1000 -> DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
            1002 -> DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
            1004 -> DECSET_BIT_SEND_FOCUS_EVENTS
            1006 -> DECSET_BIT_MOUSE_PROTOCOL_SGR
            2004 -> DECSET_BIT_BRACKETED_PASTE_MODE
            else -> -1
        }
    }

    companion object {
        const val MOUSE_LEFT_BUTTON: Int = 0

        /**
         * Mouse moving while having left mouse button pressed.
         */
        const val MOUSE_LEFT_BUTTON_MOVED: Int = 32

        const val MOUSE_WHEELUP_BUTTON: Int = 64

        const val MOUSE_WHEELDOWN_BUTTON: Int = 65

        /**
         * Used for invalid data - [...](http://en.wikipedia.org/wiki/Replacement_character#Replacement_character)
         */
        const val UNICODE_REPLACEMENT_CHAR: Int = 0xFFFD

        /* The supported terminal cursor styles. */
        const val TERMINAL_CURSOR_STYLE_BLOCK: Int = 0
        const val TERMINAL_CURSOR_STYLE_UNDERLINE: Int = 1
        const val TERMINAL_CURSOR_STYLE_BAR: Int = 2

        /**
         * Escape processing: Not currently in an escape sequence.
         */
        private const val ESC_NONE = 0

        /**
         * Escape processing: Have seen an ESC character - proceed to [.doEsc]
         */
        private const val ESC = 1

        /**
         * Escape processing: Have seen ESC POUND
         */
        private const val ESC_POUND = 2

        /**
         * Escape processing: Have seen ESC and a character-set-select ( char
         */
        private const val ESC_SELECT_LEFT_PAREN = 3

        /**
         * Escape processing: Have seen ESC and a character-set-select ) char
         */
        private const val ESC_SELECT_RIGHT_PAREN = 4

        /**
         * Escape processing: "ESC [" or CSI (Control Sequence Introducer).
         */
        private const val ESC_CSI = 6

        /**
         * Escape processing: ESC [ ?
         */
        private const val ESC_CSI_QUESTIONMARK = 7

        /**
         * Escape processing: ESC [ $
         */
        private const val ESC_CSI_DOLLAR = 8

        /**
         * Escape processing: ESC ] (AKA OSC - Operating System Controls)
         */
        private const val ESC_OSC = 10

        /**
         * Escape processing: ESC ] (AKA OSC - Operating System Controls) ESC
         */
        private const val ESC_OSC_ESC = 11

        /**
         * Escape processing: ESC [ >
         */
        private const val ESC_CSI_BIGGERTHAN = 12

        /**
         * Escape procession: "ESC P" or Device Control String (DCS)
         */
        private const val ESC_P = 13

        /**
         * Escape processing: CSI >
         */
        private const val ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14

        /**
         * Escape processing: CSI $ARGS ' '
         */
        private const val ESC_CSI_ARGS_SPACE = 15

        /**
         * Escape processing: CSI $ARGS '*'
         */
        private const val ESC_CSI_ARGS_ASTERIX = 16

        /**
         * Escape processing: CSI "
         */
        private const val ESC_CSI_DOUBLE_QUOTE = 17

        /**
         * Escape processing: CSI '
         */
        private const val ESC_CSI_SINGLE_QUOTE = 18

        /**
         * Escape processing: CSI !
         */
        private const val ESC_CSI_EXCLAMATION = 19


        /**
         * The number of parameter arguments. This name comes from the ANSI standard for terminal escape codes.
         */
        private const val MAX_ESCAPE_PARAMETERS = 16

        /**
         * Needs to be large enough to contain reasonable OSC 52 pastes.
         */
        private const val MAX_OSC_STRING_LENGTH = 8192

        /**
         * DECSET 1 - application cursor keys.
         */
        private const val DECSET_BIT_APPLICATION_CURSOR_KEYS = 1
        private const val DECSET_BIT_REVERSE_VIDEO = 1 shl 1

        /**
         * [...](http://www.vt100.net/docs/vt510-rm/DECOM): "When DECOM is set, the home cursor position is at the upper-left
         * corner of the console, within the margins. The starting point for line numbers depends on the current top margin
         * setting. The cursor cannot move outside of the margins. When DECOM is reset, the home cursor position is at the
         * upper-left corner of the console. The starting point for line numbers is independent of the margins. The cursor
         * can move outside of the margins."
         */
        private const val DECSET_BIT_ORIGIN_MODE = 1 shl 2

        /**
         * [...](http://www.vt100.net/docs/vt510-rm/DECAWM): "If the DECAWM function is set, then graphic characters received when
         * the cursor is at the right border of the page appear at the beginning of the next line. Any text on the page
         * scrolls up if the cursor is at the end of the scrolling region. If the DECAWM function is reset, then graphic
         * characters received when the cursor is at the right border of the page replace characters already on the page."
         */
        private const val DECSET_BIT_AUTOWRAP = 1 shl 3

        /**
         * DECSET 25 - if the cursor should be enabled, [.isCursorEnabled].
         */
        private const val DECSET_BIT_CURSOR_ENABLED = 1 shl 4
        private const val DECSET_BIT_APPLICATION_KEYPAD = 1 shl 5

        /**
         * DECSET 1000 - if to report mouse press&release events.
         */
        private const val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 shl 6

        /**
         * DECSET 1002 - like 1000, but report moving mouse while pressed.
         */
        private const val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 shl 7

        /**
         * DECSET 1004 - NOT implemented.
         */
        private const val DECSET_BIT_SEND_FOCUS_EVENTS = 1 shl 8

        /**
         * DECSET 1006 - SGR-like mouse protocol (the modern sane choice).
         */
        private const val DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 shl 9

        /**
         * DECSET 2004 - see [.paste]
         */
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10

        /**
         * Toggled with DECLRMM - [...](http://www.vt100.net/docs/vt510-rm/DECLRMM)
         */
        private const val DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 shl 11

        /**
         * Not really DECSET bit... - [...](http://www.vt100.net/docs/vt510-rm/DECSACE)
         */
        private const val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 shl 12
        private val PATTERN: Pattern = Pattern.compile("\r?\n")
        private val REGEX: Pattern = Pattern.compile("(\u001B|[\u0080-\u009F])")
    }
}
