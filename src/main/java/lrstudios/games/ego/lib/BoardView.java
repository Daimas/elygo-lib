/*
 * This file is part of Elygo-lib.
 * Copyright (C) 2012   Emmanuel Mathis [emmanuel *at* lr-studios.net]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lrstudios.games.ego.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import lrstudios.games.ego.lib.themes.BlackWhiteTheme;
import lrstudios.games.ego.lib.themes.DarkBoardTheme;
import lrstudios.games.ego.lib.themes.StandardTheme;
import lrstudios.games.ego.lib.themes.Theme;
import lrstudios.games.ego.lib.util.GoUtils;
import lrstudios.util.android.AndroidUtils;


public final class BoardView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = BoardView.class.getSimpleName();

    private static final double ANIM_CAPTURE_DURATION = 380.0;


    // Values from preferences
    private Theme _theme;
    private int _zoom_margin;
    private boolean _requiresValidation;
    private float _setting_offsetY;
    private boolean _offsetLarge;
    private int _gridLineSize;
    private int _stonesPadding;

    // Internal variables
    private BoardListener _listener;
    private InitListener _initListener;
    private GestureDetector _gestureDetector;
    private RefreshHandler _refreshHandler = new RefreshHandler();

    private int _surfaceWidth;
    private int _surfaceHeight;
    private int _finalWidth;
    private int _finalHeight;
    private int _surfaceSmallestSize;
    private int _surfaceLargestSize;
    private int _stoneSize;
    private int _leftMargin;
    private int _topMargin;
    private float _answerCircleRadius;
    private float _offsetY;
    private Point _crossCursor = new Point(-1, -1);
    private Point _prevCrossCursor = new Point(-1, -1);
    private Point _fixedCrossCursor;
    private Point _moveValidated;
    private Rect _baseBounds;
    private Rect _clipBounds;
    private Rect _tempBounds = new Rect();
    private boolean _isZoom;
    private boolean _isMoveLegal;
    private boolean _forceRequiresValidation;
    private boolean _forceHideCoordinates;
    private boolean _allowIllegalMoves;
    private boolean _playLock;
    private boolean _showAnswers;
    private boolean _showVariations;
    private boolean _showFinalStatus;
    private boolean _showCoordinates;
    private boolean _displayNextVariations;
    private boolean _reverseColors;
    private boolean _monocolor;
    private boolean _externalValidation;

    private List<BoardAnimation> _anim_prisonersList = new ArrayList<BoardAnimation>();
    private Drawable _anim_captureBlackDrawable;
    private Drawable _anim_captureWhiteDrawable;

    private Point _pt_coord2point = new Point(-1, -1);
    private Paint _stdBitmapPaint;
    private Drawable _cursorDrawableBlack;
    private Drawable _cursorDrawableWhite;

    private GoGame _game;
    private int _size;


    // Gesture detector callbacks
    private final class BoardGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            if (_playLock)
                return true;

            float x = event.getX();
            float y = event.getY();

            if (_requiresValidation) {
                final Point coords = _cache_getBoardCoordsAtLocation(x, y);
                if (_crossCursor.x >= 0 && _isMoveLegal && coords.equals(_crossCursor.x, _crossCursor.y))
                    _moveValidated = new Point(coords);
                else
                    _moveValidated = null;
            }
            if (_moveValidated == null) {
                final Point coords = _cache_getBoardCoordsAtLocation(x, y - _offsetY);
                moveCrossCursor(coords);
            }
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent event) {
            if (_requiresValidation && !_playLock && _moveValidated != null && !_externalValidation)
                validateCurrentMove();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent event) {
        }
    }

    private boolean onUp(MotionEvent event) {
        if (_playLock)
            return true;

        if (!_requiresValidation) {
            final Point coords = _cache_getBoardCoordsAtLocation(event.getX(), event.getY() - _offsetY);

            if (_crossCursor.x >= 0 && _listener != null && _isInBounds(coords) &&
                    (_allowIllegalMoves || _game.isLegal(coords.x, coords.y)))
            {
                _listener.onPress(coords.x, coords.y);
            }
            moveCrossCursor(null);
        }
        else if (!_isMoveLegal) {
            moveCrossCursor(null);
        }

        return true;
    }

    private boolean onMove(MotionEvent event) {
        if (!_playLock && _moveValidated == null) {
            final Point coords = _cache_getBoardCoordsAtLocation(event.getX(), event.getY() - _offsetY);
            moveCrossCursor(coords);
        }

        return true;
    }


    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (_gestureDetector == null || _clipBounds == null)
            return false;

        switch (e.getAction()) {
            // We don't use the onScroll event of the GestureDetector because it stops sending events after a long press
            case MotionEvent.ACTION_MOVE:
                onMove(e);
                break;
            case MotionEvent.ACTION_UP:
                onUp(e);
                break;
        }
        return _gestureDetector.onTouchEvent(e);
    }


    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (!isInEditMode())
            readPreferences();

        _stdBitmapPaint = new Paint();
        _answerCircleRadius = getResources().getDimension(R.dimen.boardview_answer_circle_radius);

        getHolder().setFormat(PixelFormat.TRANSPARENT);
        getHolder().addCallback(this);
    }


    public void readPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        _zoom_margin = Integer.parseInt(prefs.getString("tsumegoMarginPref", "3"));
        _stonesPadding = Integer.parseInt(prefs.getString("stonePaddingPref", "1"));
        _gridLineSize = Integer.parseInt(prefs.getString("gridLinesSizePref", "1"));
        _requiresValidation = _forceRequiresValidation || prefs.getBoolean("requiresValidationPref", false);
        _showCoordinates = prefs.getBoolean("showCoordinates", false);
        String skin = prefs.getString("themePref", "standard");
        String inputType = prefs.getString("inputType", "0");

        if (prefs.contains("monocolorPref"))
            _monocolor = prefs.getBoolean("monocolorPref", false);

        if (skin.equals("blackwhite")) {
            if (!(_theme instanceof BlackWhiteTheme))
                _theme = new BlackWhiteTheme(getContext());
        }
        else if (skin.equals("standard")) {
            if (_theme instanceof DarkBoardTheme || !(_theme instanceof StandardTheme))
                _theme = new StandardTheme(getContext());
        }
        else {
            if (!(_theme instanceof DarkBoardTheme))
                _theme = new DarkBoardTheme(getContext());
        }

        _setting_offsetY = inputType.startsWith("offset") ? getResources().getDimension(R.dimen.stone_input_offset) : 0;
        _offsetLarge = inputType.equals("offsetLarge");
    }


    /**
     * Changes the current board shown by this view.
     */
    public void changeGame(GoGame game, boolean allowZoom) {
        _isZoom = allowZoom;
        _game = game;

        _size = _game.board.getSize();
        _showAnswers = false;
        _baseBounds = null;
        recreateGraphics();
    }

    /**
     * Gets the current cursor position (never null, but it's set to -1,-1 if no intersection is selected).
     */
    public Point getCursorPosition() {
        return _crossCursor;
    }

    /**
     * Validates the current move. This will send an onPress() event to the listener
     * if the move was validated successfully.
     */
    public boolean validateCurrentMove() {
        if (_crossCursor.x < 0)
            return false;

        if (_listener != null) {
            if (_externalValidation)
                _listener.onPress(_crossCursor.x, _crossCursor.y);
            else
                _listener.onPress(_moveValidated.x, _moveValidated.y);
        }

        moveCrossCursor(null);
        return true;
    }

    public void setZoomMargin(int margin) {
        if (margin < 0)
            margin = 0;
        else if (margin > _size)
            margin = _size;

        _zoom_margin = margin;
        recreateGraphics();
    }

    public int getZoomMargin() {
        return _zoom_margin;
    }

    /**
     * Defines whether playing moves needs validation or not (this overrides the preference).
     */
    public void setMoveValidation(boolean needValidation) {
        _requiresValidation = needValidation;
        _forceRequiresValidation = needValidation;
    }

    public void setExternalValidation(boolean external) {
        _requiresValidation = external;
        _forceRequiresValidation = external;
        _externalValidation = external;
    }

    public void setHideCoordinates(boolean hide) {
        _forceHideCoordinates = hide;
        invalidate();
    }

    /**
     * Allows or prevents the view to fire onPress events for illegal moves.
     */
    public void allowIllegalMoves(boolean allow) {
        _allowIllegalMoves = allow;
    }

    /**
     * Prevents the user to play a move.
     */
    public void lockPlaying() {
        lockPlaying(true);
    }

    /**
     * If set to true, prevents the user to play a move.
     */
    public void lockPlaying(boolean lock) {
        lockPlaying(lock, false);
    }

    public void lockPlaying(boolean lock, boolean lockCrossCursor) {
        _playLock = lock;
        if (lock && !lockCrossCursor)
            moveCrossCursor(null);
    }

    /**
     * Allows the user to play a move.
     */
    public void unlockPlaying() {
        lockPlaying(false);
    }

    public void showVariations(boolean show) {
        _showVariations = show;
    }


    /**
     * Shows or hides the good and wrong variations of the current problem by drawing marks on the board.
     */
    public void showAnswers(boolean show) {
        _showAnswers = show;
        invalidate();
    }

    public void showCoordinates(boolean show) {
        _showCoordinates = show;
        invalidate();
    }

    /**
     * If the answers are already shown, they will become hidden and vice versa.
     */
    public void toggleAnswers() {
        showAnswers(!_showAnswers);
    }

    public boolean isAnswerDisplayed() {
        return _showAnswers;
    }

    /**
     * Reverses (or not) all colors of the current game (only visually, it doesn't alter the game).
     */
    public void setReverseColors(boolean reverse) {
        _reverseColors = reverse;
    }

    /**
     * If set to true, all black stones will become white.
     */
    public void setMonocolor(boolean enable) {
        _monocolor = enable;
        invalidate();
    }

    public void setDisplayNextVariations(boolean displayNext) {
        _displayNextVariations = displayNext;
        invalidate();
    }

    public boolean displayNextVariations() {
        return _displayNextVariations;
    }

    /**
     * Returns the current visual theme of this board.
     */
    public Theme getCurrentTheme() {
        return _theme;
    }


    public void setFixedCrossCursor(Point coords) {
        _fixedCrossCursor = coords;
        invalidate();
    }

    /**
     * Draws the cross cursor to the specified location.
     */
    private void moveCrossCursor(Point coords) {
        if (_isInBounds(coords)) {
            _crossCursor.set(coords.x, coords.y);
            _isMoveLegal = _allowIllegalMoves || _game.isLegal(coords.x, coords.y);
        }
        else {
            _crossCursor.set(-1, -1);
        }

        if (!_prevCrossCursor.equals(_crossCursor.x, _crossCursor.y)) {
            if (_listener != null)
                _listener.onCursorMoved(_crossCursor.x, _crossCursor.y);
            _prevCrossCursor.set(_crossCursor.x, _crossCursor.y);
        }
        invalidate(); // TODO repaint cross cursor only
    }


    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        boolean isLandscape = width > height;
        _surfaceWidth = width;
        _surfaceHeight = height;
        _surfaceSmallestSize = (isLandscape) ? height : width;
        _surfaceLargestSize = (isLandscape) ? width : height;
        recreateGraphics();
    }

    /**
     * Recreates graphic objects when a visual setting is changed (zoom, board size, ...).
     */
    public void recreateGraphics() {
        if (_surfaceWidth <= 0 || _surfaceHeight <= 0)
            return;

        _computeDimensions(_isZoom && _baseBounds == null);

        if (!_offsetLarge || (_clipBounds.width() > 15 && _clipBounds.height() > 15))
            _offsetY = _setting_offsetY;
        else
            _offsetY = 0;

        _leftMargin = (_surfaceWidth - _finalWidth) / 2;
        _topMargin = (_surfaceHeight - _finalHeight) / 2;
        _crossCursor.set(-1, -1);

        Theme.Config config = new Theme.Config(
                _surfaceWidth, _surfaceHeight, _stoneSize, _size, _gridLineSize, _stonesPadding);
        _theme.init(config);
        if (_initListener != null)
            _initListener.onThemeLoaded(_theme);

        Resources res = getContext().getResources();
        _cursorDrawableBlack = new BitmapDrawable(res, _theme.blackStoneBitmap);
        _cursorDrawableBlack.setAlpha(98);
        _cursorDrawableWhite = new BitmapDrawable(res, _theme.whiteStoneBitmap);
        _cursorDrawableWhite.setAlpha(98);

        _anim_captureBlackDrawable = new BitmapDrawable(res, _theme.blackStoneBitmap);
        _anim_captureWhiteDrawable = new BitmapDrawable(res, _theme.whiteStoneBitmap);
        invalidate();
    }


    private void _computeDimensions(boolean allowRotation) {
        final Rect maxBounds = new Rect(0, 0, _size - 1, _size - 1);
        _clipBounds = (_baseBounds == null) ? _game.board.getBounds() : _baseBounds;

        // _baseBounds avoids rotating/zooming the same problem multiple times (this may
        // happen especially when the user go to the preferences screen during playing
        // and go back).
        _baseBounds = new Rect(_clipBounds);

        if (!_isZoom || _clipBounds.right < 0 || _clipBounds.bottom < 0) {
            _clipBounds.set(0, 0, _size - 1, _size - 1);
            allowRotation = false;
        }
        else {
            AndroidUtils.Rect_addMargin(_clipBounds, (_size < 19 ? 99 : _zoom_margin), maxBounds);
        }

        int hSize = Math.max(1, _clipBounds.width() + 1);
        int vSize = Math.max(1, _clipBounds.height() + 1);
        int zoom_largestBoardSize = (hSize > vSize) ? hSize : vSize;
        int zoom_smallestBoardSize = (hSize > vSize) ? vSize : hSize;

        int largestSide_maxStoneSize = _surfaceLargestSize / zoom_largestBoardSize;
        int smallestSide_maxStoneSize = _surfaceSmallestSize / zoom_smallestBoardSize;

        _stoneSize = (largestSide_maxStoneSize < smallestSide_maxStoneSize) ? largestSide_maxStoneSize : smallestSide_maxStoneSize;

        int highestSize = _stoneSize * zoom_largestBoardSize;
        int lowestSize = _stoneSize * zoom_smallestBoardSize;

        _finalWidth = (_surfaceWidth > _surfaceHeight) ? highestSize : lowestSize;
        _finalHeight = (_surfaceWidth > _surfaceHeight) ? lowestSize : highestSize;

        if (allowRotation
                && ((hSize > vSize && _finalWidth < _finalHeight)
                || (hSize < vSize && _finalWidth > _finalHeight)))
        {
            _game.rotateCCW();
            _baseBounds = null;
            _computeDimensions(false);
        }
        // If the board is zoomed...
        else if (hSize < _size || vSize < _size) {
            // If an entire side of the board is shown, there may be some space left on this side. We remove it
            int spaceWidth = (_surfaceWidth - _finalWidth) / _stoneSize;

            _finalWidth += spaceWidth * _stoneSize;

            int leftWidth = spaceWidth - _clipBounds.left;
            if (leftWidth < 0)
                leftWidth = 0;
            _clipBounds.left -= spaceWidth - leftWidth;
            _clipBounds.right += leftWidth;

            int rightSpace = (_surfaceWidth - (_clipBounds.right - _clipBounds.left + 1) * _stoneSize) / _stoneSize;
            if (rightSpace + _clipBounds.width() >= _size)
                rightSpace = _size - 1 - _clipBounds.width();
            int bottomSpace = (_surfaceHeight - (_clipBounds.bottom - _clipBounds.top + 1) * _stoneSize) / _stoneSize;
            if (bottomSpace + _clipBounds.height() >= _size)
                bottomSpace = _size - 1 - _clipBounds.height();

            if (rightSpace > 0) {
                _clipBounds.right += rightSpace;
                _finalWidth += _stoneSize * rightSpace;
            }
            if (bottomSpace > 0) {
                _clipBounds.bottom += bottomSpace;
                _finalHeight += _stoneSize * bottomSpace;
            }

            AndroidUtils.Rect_crop(_clipBounds, maxBounds);
        }
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (_game == null) {
            _game = new GoGame(9, 6.5, 0);
            changeGame(_game, false);
        }

        setWillNotDraw(false); // Necessary for `onDraw()` to be called
        _gestureDetector = new GestureDetector(getContext(), new BoardGestureListener());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        _gestureDetector = null;
    }


    @Override
    public void onDraw(Canvas canvas) {
        // Background
        _theme.drawBackground(canvas, 0, 0, _surfaceWidth, _surfaceHeight);

        // Coordinates
        if (_showCoordinates && !_forceHideCoordinates) {
            _theme.coordinatesPaint.setTextSize(_stoneSize / 2.2f);
            _theme.coordinatesPaint.getTextBounds("A", 0, 1, _tempBounds);
            int textHeight = _tempBounds.height();

            for (int x = _clipBounds.left; x <= _clipBounds.right; x++)
                canvas.drawText(GoUtils.getCoordinateChars(x),
                        _leftMargin + _stoneSize / 2f + _stoneSize * x,
                        textHeight + (_stoneSize / 2f - textHeight + _topMargin) / 2f,
                        _theme.coordinatesPaint);

            for (int y = _clipBounds.top; y <= _clipBounds.bottom; y++)
                canvas.drawText(Integer.toString(_size - y),
                        _stoneSize / 4f + _leftMargin / 2f,
                        _topMargin + _stoneSize / 2f + _stoneSize * y + textHeight / 2f,
                        _theme.coordinatesPaint);
        }

        canvas.translate(_leftMargin, _topMargin);

        // Grid
        canvas.save();
        canvas.clipRect(0, 0, _finalWidth, _finalHeight);

        _theme.drawGrid(canvas, _clipBounds);

        canvas.restore();

        // Stones
        final GoBoard board = _game.board;
        final int boardSize = board.getSize();
        final byte[] colors = board.getBoardArray();

        for (int curX = _clipBounds.left; curX <= _clipBounds.right; curX++) {
            for (int curY = _clipBounds.top; curY <= _clipBounds.bottom; curY++) {
                int x = curX - _clipBounds.left;
                int y = curY - _clipBounds.top;

                boolean showStatus = _showFinalStatus;
                if (showStatus) {
                    byte color = _game.getFinalStatus(curX, curY);
                    if (_reverseColors) {
                        switch (color) {
                            case GoBoard.BLACK_TERRITORY:
                                color = GoBoard.WHITE_TERRITORY;
                                break;
                            case GoBoard.WHITE_TERRITORY:
                                color = GoBoard.BLACK_TERRITORY;
                                break;
                            case GoBoard.DEAD_BLACK_STONE:
                                color = GoBoard.DEAD_WHITE_STONE;
                                break;
                            case GoBoard.DEAD_WHITE_STONE:
                                color = GoBoard.DEAD_BLACK_STONE;
                                break;
                        }
                    }
                    if (color == GoBoard.BLACK_TERRITORY) {
                        _theme.blackTerritory.setBounds(
                                _stoneSize * x, _stoneSize * y,
                                _stoneSize * (x + 1), _stoneSize * (y + 1));
                        _theme.blackTerritory.draw(canvas);
                    }
                    else if (color == GoBoard.WHITE_TERRITORY) {
                        _theme.whiteTerritory.setBounds(
                                _stoneSize * x, _stoneSize * y,
                                _stoneSize * (x + 1), _stoneSize * (y + 1));
                        _theme.whiteTerritory.draw(canvas);
                    }
                    else if (color == GoBoard.DEAD_BLACK_STONE) {
                        _theme.drawDeadBlackStone(canvas, _stoneSize * x, _stoneSize * y, _stoneSize);
                    }
                    else if (color == GoBoard.DEAD_WHITE_STONE) {
                        _theme.drawDeadWhiteStone(canvas, _stoneSize * x, _stoneSize * y, _stoneSize);
                    }
                    else {
                        showStatus = false;
                    }
                }

                if (!showStatus) {
                    byte color = colors[curY * boardSize + curX];
                    if (_reverseColors && color != GoBoard.EMPTY)
                        color = GoBoard.getOppositeColor(color);
                    if (color == GoBoard.BLACK && !_monocolor)
                        canvas.drawBitmap(_theme.blackStoneBitmap, _stoneSize * x + _stonesPadding, _stoneSize * y + _stonesPadding, _stdBitmapPaint);
                    else if (color == GoBoard.WHITE || (color == GoBoard.BLACK && _monocolor))
                        canvas.drawBitmap(_theme.whiteStoneBitmap, _stoneSize * x + _stonesPadding, _stoneSize * y + _stonesPadding, _stdBitmapPaint);
                    else if (color == GoBoard.ANY)
                        _theme.drawAnyStone(canvas, _stoneSize * x, _stoneSize * y, _stoneSize);
                }
            }
        }

        // Variations
        if (_showVariations && !_showFinalStatus) {
            GameNode parentMove = _game.getCurrentNode().parentNode;
            if (parentMove != null) {
                for (GameNode move : parentMove.nextNodes) {
                    if (_isInBounds(move.x, move.y) && _game.board.getColor(move.x, move.y) == GoBoard.EMPTY) {
                        byte color = move.color;
                        if (_reverseColors && color != GoBoard.EMPTY)
                            color = GoBoard.getOppositeColor(color);

                        if (color == GoBoard.BLACK)
                            _theme.drawBlackVariation(canvas, _stoneSize * move.x, _stoneSize * move.y, _stoneSize);
                        else if (color == GoBoard.WHITE)
                            _theme.drawWhiteVariation(canvas, _stoneSize * move.x, _stoneSize * move.y, _stoneSize);
                    }
                }
            }
        }

        // Marks
        final float MARK_PADDING = 4.5f + ((_size < 10) ? 0.5f : 0f);
        for (BoardMark mark : board.getMarks()) {
            int x = mark.x - _clipBounds.left;
            int y = mark.y - _clipBounds.top;
            byte color = colors[mark.y * boardSize + mark.x];
            if (_reverseColors && color != GoBoard.EMPTY)
                color = GoBoard.getOppositeColor(color);

            // Letters and digits
            if (mark.type == BoardMark.MARK_LABEL) {
                Paint paint = (color == GoBoard.BLACK) ? _theme.blackLabelPaint : // TODO should be in _theme (no public Paint at all would be better)
                        (color == GoBoard.WHITE) ? _theme.whiteLabelPaint : _theme.boardLabelPaint;

                String markText = Character.toString(mark.getLabel()).toLowerCase();
                paint.getTextBounds(markText, 0, markText.length(), _tempBounds);

                // Don't draw the grid on empty intersections as they are hard to read
                if (GoBoard.EMPTY == colors[mark.y * boardSize + mark.x])
                    _theme.drawBackground(canvas, x * _stoneSize, y * _stoneSize, (x + 1) * _stoneSize, (y + 1) * _stoneSize);

                canvas.drawText(markText,
                        _stoneSize * x + (_stoneSize / 2.0f) - AndroidUtils.getTextWidth(markText, paint) / 2.0f, // getTextWidth() is more accurate than getBounds()
                        _stoneSize * y + (_stoneSize / 2.0f) + _tempBounds.height() / 2.0f,
                        paint);
            }
            // Shapes
            else {
                Paint paint = (color == GoBoard.BLACK) ? _theme.blackMarkPaint :
                        (color == GoBoard.WHITE) ? _theme.whiteMarkPaint : _theme.boardMarkPaint;

                ShapeDrawable markShape = null;
                switch (mark.type) {
                    case BoardMark.MARK_CIRCLE:
                        markShape = _theme.circleMark;
                        break;
                    case BoardMark.MARK_CROSS:
                        markShape = _theme.crossMark;
                        break;
                    case BoardMark.MARK_SQUARE:
                        markShape = _theme.squareMark;
                        break;
                    case BoardMark.MARK_TRIANGLE:
                        markShape = _theme.triangleMark;
                        break;
                }

                if (markShape != null) {
                    markShape.getPaint().set(paint);
                    markShape.setBounds(
                            Math.round(_stoneSize * x + _stoneSize / MARK_PADDING),
                            Math.round(_stoneSize * y + _stoneSize / MARK_PADDING),
                            Math.round(_stoneSize * x + _stoneSize - _stoneSize / MARK_PADDING),
                            Math.round(_stoneSize * y + _stoneSize - _stoneSize / MARK_PADDING));
                    markShape.draw(canvas);
                }
            }
        }

        // Problem solution
        if (_showAnswers) {
            for (GameNode node : _game.getCurrentNode().nextNodes) {
                if (node.value < 0)
                    continue;

                int x = node.x - _clipBounds.left;
                int y = node.y - _clipBounds.top;

                canvas.drawCircle(
                        _stoneSize * x + _stoneSize / 2,
                        _stoneSize * y + _stoneSize / 2,
                        _answerCircleRadius,
                        (node.value > 0) ? _theme.goodVariationPaint : _theme.badVariationPaint);

                if (node.value > 0) {
                    _theme.goodVariationPaint2.setStrokeWidth(_answerCircleRadius / 2.7f);
                    canvas.drawCircle(
                            _stoneSize * x + _stoneSize / 2,
                            _stoneSize * y + _stoneSize / 2,
                            _answerCircleRadius * 1.5f,
                            _theme.goodVariationPaint2);
                }
            }
        }

        // Cross cursor
        boolean useFixedCursor = _fixedCrossCursor != null;
        Point crossCursor = useFixedCursor ? _fixedCrossCursor : _crossCursor;

        if (crossCursor.x >= 0) {
            int x = crossCursor.x - _clipBounds.left;
            int y = crossCursor.y - _clipBounds.top;

            Paint paint = (useFixedCursor || _isMoveLegal) ? _theme.crossCursorPaint : _theme.illegalCrossCursorPaint;
            canvas.drawLine(
                    _stoneSize * x + _stoneSize / 2f, -_topMargin,
                    _stoneSize * x + _stoneSize / 2f, _surfaceHeight - _topMargin, paint);
            canvas.drawLine(
                    -_leftMargin, _stoneSize * y + _stoneSize / 2f,
                    _surfaceWidth - _leftMargin, _stoneSize * y + _stoneSize / 2f, paint);

            if (useFixedCursor || _isMoveLegal) {
                Drawable cursor;
                byte nextPlayer = _game.getNextPlayer();
                if (nextPlayer == GoBoard.ANY)
                    cursor = _theme.anyStoneDrawable;
                else if (nextPlayer == GoBoard.EMPTY)
                    cursor = _theme.blackTerritory;
                else if (!_monocolor && ((nextPlayer == GoBoard.BLACK && !_reverseColors) || (nextPlayer == GoBoard.WHITE && _reverseColors)))
                    cursor = _cursorDrawableBlack;
                else
                    cursor = _cursorDrawableWhite;
                cursor.setBounds(
                        _stoneSize * x + _stonesPadding, _stoneSize * y + _stonesPadding,
                        _stoneSize * x - _stonesPadding + this._stoneSize, _stoneSize * y - _stonesPadding + this._stoneSize);
                cursor.draw(canvas);
            }
        }

        // Animations
        if (_anim_prisonersList.size() > 0) {
            for (BoardAnimation anim : _anim_prisonersList)
                anim.draw(canvas);
        }
    }


    public void addPrisoners(Iterable<LightCoords> prisoners) {
        if (prisoners == null)
            return;

        for (LightCoords coords : prisoners) {
            int x = (coords.x - _clipBounds.left) * _stoneSize;
            int y = (coords.y - _clipBounds.top) * _stoneSize;

            Drawable anim;
            if (!_monocolor && ((coords.color == GoBoard.BLACK && !_reverseColors) || (coords.color == GoBoard.WHITE && _reverseColors)))
                anim = _anim_captureBlackDrawable;
            else
                anim = _anim_captureWhiteDrawable;
            _anim_prisonersList.add(new BoardAnimation(anim,
                    new Rect(x + _stonesPadding, y + _stonesPadding, x + _stoneSize - _stonesPadding, y + _stoneSize - _stonesPadding),
                    new Rect(x + _stoneSize / 2, y + _stoneSize / 2, x + _stoneSize / 2, y + _stoneSize / 2),
                    255, 85));
        }

        _updateAnimations();
    }


    public void setBoardListener(BoardListener listener) {
        _listener = listener;
    }

    public void setInitListener(InitListener listener) {
        _initListener = listener;
    }

    /**
     * Shows (or not) the final status of the current game.
     */
    public void showFinalStatus(boolean show) {
        _showFinalStatus = show;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int sizeWidth = MeasureSpec.getSize(widthMeasureSpec);
        int modeWidth = MeasureSpec.getMode(widthMeasureSpec);
        int sizeHeight = MeasureSpec.getSize(heightMeasureSpec);
        int modeHeight = MeasureSpec.getMode(heightMeasureSpec);

        // Rules :
        // - If at least one of the measure specs is set to AT_MOST, make the view a square
        // - Else, set the view to use all of the available space

        if (modeWidth == MeasureSpec.AT_MOST || modeHeight == MeasureSpec.AT_MOST) {
            int minSize = Math.min(sizeWidth, sizeHeight);
            setMeasuredDimension(minSize, minSize);
        }
        else {
            setMeasuredDimension(sizeWidth, sizeHeight);
        }
    }


    private boolean _isInBounds(Point coords) {
        return coords != null && _isInBounds(coords.x, coords.y);
    }

    private boolean _isInBounds(int x, int y) {
        return _clipBounds == null || (x >= _clipBounds.left && x <= _clipBounds.right
                && y >= _clipBounds.top && y <= _clipBounds.bottom);
    }


    /**
     * Updates all animations on the board.
     */
    private void _updateAnimations() {
        final double SLEEP_INTERVAL = 1000.0 / 50.0;

        if (_anim_prisonersList.size() > 0) {
            Iterator<BoardAnimation> it = _anim_prisonersList.iterator();
            while (it.hasNext()) {
                BoardAnimation anim = it.next();
                anim.update(SLEEP_INTERVAL / ANIM_CAPTURE_DURATION);

                if (anim.isFinished())
                    it.remove();

                invalidate(
                        _leftMargin + anim.startX, _topMargin + anim.startY,
                        _leftMargin + anim.startX + anim.startWidth, _topMargin + anim.startY + anim.startHeight);
            }

            _refreshHandler.postUpdate((long) SLEEP_INTERVAL);
        }
    }


    /**
     * Returns the board coordinates located at the specified (x, y) point on the surface.
     * WARNING : for performance issues, the reference returned will always be the same.
     */
    private Point _cache_getBoardCoordsAtLocation(float x, float y) {
        int finalX = (int) ((x - _leftMargin) / _stoneSize) + _clipBounds.left;
        int finalY = (int) ((y - _topMargin) / _stoneSize) + _clipBounds.top;
        _pt_coord2point.set(finalX, finalY);
        return _pt_coord2point;
    }


    // Handle view animations
    private final class RefreshHandler extends Handler {
        static final int _MSG_REPAINT = 5555;

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == _MSG_REPAINT)
                BoardView.this._updateAnimations();
        }

        /**
         * Updates all animations, then refresh the View.
         */
        public void postUpdate(long delayMillis) {
            removeMessages(_MSG_REPAINT);
            sendMessageDelayed(obtainMessage(_MSG_REPAINT), delayMillis);
        }
    }


    public interface BoardListener {
        /** Called when the user clicks on an intersection of the board. */
        void onPress(int x, int y);

        /** Called when the user moves the cross cursor to a new location. */
        void onCursorMoved(int x, int y);
    }

    public interface InitListener {
        void onThemeLoaded(Theme theme);
    }
}
