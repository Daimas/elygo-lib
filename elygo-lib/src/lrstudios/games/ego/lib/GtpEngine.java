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
import android.util.Log;

import java.util.Properties;


/**
 * Represents a Go engine which uses the standard GTP protocol to communicate.
 */
public abstract class GtpEngine {
    private static final String TAG = "GtpEngine";

    private static final String _BOARD_LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"; // no 'I'
    private static final String _BLACK_NAME = "black";
    private static final String _WHITE_NAME = "white";

    private byte _playerColor;
    private int _boardSize;
    private GoGame _game;
    protected Context _context;


    /**
     * Initializes the engine with the specified set of properties. Returns false if there was an error.
     */
    public abstract boolean init(Properties properties);

    /**
     * Sends a GTP command to the engine.
     *
     * @param command The GTP command to send.
     * @return The response of the engine.
     */
    public abstract String sendGtpCommand(String command);

    /**
     * Returns the name of the engine, which can be displayed as the player name (try to keep it short).
     */
    public abstract String getName();

    /**
     * Returns the version of the engine.
     */
    public abstract String getVersion();


    public GtpEngine(Context context) {
        _context = context;
    }

    /**
     * Starts a new game.
     *
     * @param boardSize   The board size, between 4 and 19.
     * @param playerColor The player's color, either GoBoard.BLACK or GoBoard.WHITE.
     * @param komi        The komi for this game. The komi can be a negative value.
     * @param handicap    The handicap for this game (either 0 or a value between 2 and 9),
     *                    which will be placed automatically.
     * @throws IllegalArgumentException
     */
    public void newGame(int boardSize, byte playerColor, double komi, int handicap) {
        _newGame(boardSize, playerColor, komi, handicap, true);
    }


    /**
     * Starts a new game at the end of the main variation of the specified game.
     */
    public void newGame(GoGame game) {
        _game = game;
        _newGame(_game.board.getSize(), _game.getNextPlayer(), _game.getKomi(), _game.getHandicap(), false);

        GameNode move = _game.getBaseNode();
        while (move.nextNodes.size() > 0) {
            move = move.nextNodes.get(0);
            _playMove(new Coords(move.x, move.y), move.color, false);
        }

        _game.gotoLastMove();

        if (_game.getCurrentMoveNumber() > 0) {
            byte color = _game.getCurrentNode().color;
            if (color == GoBoard.WHITE || color == GoBoard.BLACK)
                _playerColor = GoBoard.getOppositeColor(color);
        }
    }


    protected void _newGame(int boardSize, byte playerColor, double komi, int handicap, boolean createGame) {
        if (playerColor != GoBoard.BLACK && playerColor != GoBoard.WHITE)
            throw new IllegalArgumentException("The player color is invalid (" + playerColor + ").");

        sendGtpCommand("boardsize " + boardSize);
        sendGtpCommand("komi " + ((int) (komi * 10.0) / 10.0));
        sendGtpCommand("clear_board");

        _playerColor = playerColor;
        _boardSize = boardSize;

        // Lower the handicap until the engine agrees with it
        String cmdStatus = "";
        while (handicap > 0 && !cmdSuccess((cmdStatus = sendGtpCommand("fixed_handicap " + handicap))))
            handicap--;

        if (createGame)
            _game = new GoGame(boardSize, komi, 0); // TODO find a better way to handle handicap

        // Place it on the board (the handicap stone coordinates were returned by the "fixed_handicap" command)
        if (cmdStatus.length() > 1) {
            String[] handicapCoords = cmdStatus.replace("\n", "").split(" ");

            for (String handiCoord : handicapCoords) {
                try {
                    Coords coords = _str2point(handiCoord);
                    if (coords != null)
                        _game.addStone(coords.x, coords.y, GoBoard.BLACK);
                }
                catch (Exception ignored) { // Ignore whitespaces and other useless characters
                }
            }
        }
        _game.info.handicap = handicap;
        if (handicap > 1)
            _game.switchCurrentPlayer();
    }


    /**
     * Plays a move on the specified coordinates (gtp command "play").
     *
     * @param coords The coordinates, zero-based ( (0, 0) is the top left intersection).
     *               Set them to (-1, -1) to pass.
     * @return true if the move was legal, false otherwise.
     * @throws IllegalArgumentException
     */
    public boolean playMove(Coords coords) {
        return _playMove(coords, _playerColor, true);
    }


    /**
     * Plays a move on the specified coordinates (gtp command "play").
     *
     * @param coords The coordinates, zero-based (The point (0, 0) is the top left intersection).
     *               Set to (-1, -1) to pass.
     * @param color  The stone color.
     * @return true if the move was legal, false otherwise.
     * @throws IllegalArgumentException
     */
    public boolean playMove(Coords coords, byte color) {
        return _playMove(coords, color, true);
    }


    protected boolean _playMove(Coords coords, byte color, boolean playMove) {
        if ((coords.x != -1 || coords.y != -1) && (coords.x < 0 || coords.x >= _boardSize || coords.y < 0 || coords.y >= _boardSize))
            throw new IllegalArgumentException("The coordinates are out of bounds.");

        String cmd = String.format("play %1$s %2$s", _getColorString(color), _point2str(coords));
        boolean success = cmdSuccess(sendGtpCommand(cmd));
        if (success && playMove)
            _game.playMove(coords);
        return success;
    }


    /**
     * Tells the engine to play the next move.
     *
     * @return The move played by the engine ( (0, 0) is the top left intersection).
     *         Returns (-1, -1) if the engine passes.
     */
    public Coords genMove() {
        String move = sendGtpCommand("genmove " + _getBotColorString());
        Coords coords = _str2point(move.substring(move.indexOf(' ') + 1).trim());
        //Log.v(TAG, "Bot played " + move + ", coords are " + coords);

        if (coords.x == -1)
            _game.pass();
        else if (coords.x == -3)
            _game.resign(getBotColor());
        else
            _game.playMove(coords);
        return coords;
    }


    /**
     * Undo the last move from the player.
     *
     * @param allowDoubleUndo Also undo the answer move from the engine (if there is one).
     * @return false if there was no move to undo.
     */
    public boolean undo(boolean allowDoubleUndo) {
        boolean doubleUndo = allowDoubleUndo &&
                (_game.getNextPlayer() == _playerColor && _game.getCurrentNode().x >= -1);

        String result = sendGtpCommand("undo");
        if (cmdSuccess(result)) {
            _game.undo(true);
            if (doubleUndo && cmdSuccess(sendGtpCommand("undo")))
                _game.undo(true);
            return true;
        }
        else if (result.contains("cannot undo")) {
            Log.v(TAG, "Faking undo...");
            // Being able to undo moves on android is necessary (because mistakes happen very
            // often on small screens), so we fake an undo by starting a new game and replaying
            // the whole game tree except the last move.
            if (_game.undo(true) != null) {
                if (doubleUndo)
                    _game.undo(true);
                newGame(_game);
                return true;
            }
        }
        return false;
        //return cmdSuccess(sendGtpCommand("gg-undo " + (doubleUndo ? 2 : 1)));
    }

    public boolean setLevel(int level) {
        return cmdSuccess(sendGtpCommand("level " + level));
    }

    /**
     * The engine will set the status of every stone on the board (dead, white territory,
     * or black territory). The result can be obtained with getGame().getFinalStatus().
     * Note that this can take a long time to execute.
     */
    public void askFinalStatus() {
        String[] coords = sendGtpCommand("final_status_list white_territory").split(" ");
        int len = coords.length;
        for (int i = 1; i < len; i++) {
            Coords pt = _str2point(coords[i]);
            if (pt != null)
                _game.setFinalStatus(pt.x, pt.y, GoBoard.WHITE_TERRITORY);
        }

        coords = sendGtpCommand("final_status_list black_territory").split(" ");
        len = coords.length;
        for (int i = 1; i < len; i++) {
            Coords pt = _str2point(coords[i]);
            if (pt != null)
                _game.setFinalStatus(pt.x, pt.y, GoBoard.BLACK_TERRITORY);
        }

        coords = sendGtpCommand("final_status_list dead").split("[ \n]");
        len = coords.length;
        for (int i = 1; i < len; i++) {
            Coords pt = _str2point(coords[i]);
            if (pt != null) {
                _game.setFinalStatus(pt.x, pt.y,
                        (_game.board.getColor(pt.x, pt.y) == GoBoard.WHITE) ?
                                GoBoard.DEAD_WHITE_STONE : GoBoard.DEAD_BLACK_STONE);
            }
        }
    }


    /**
     * The engine will compute the final result of the game (or null if something failed) and
     * set it as the result of the underlying game.
     */
    public GoGameResult computeFinalScore() {
        String finalScore = sendGtpCommand("final_score").split(" ")[1];
        GoGameResult result = GoGameResult.tryParse(finalScore);
        if (result != null)
            _game.info.result = result;
        return result;
    }


    /**
     * Returns true if the next player to play is the engine, false otherwise.
     */
    public boolean isBotTurn() {
        return _playerColor != _game.getNextPlayer();
    }


    /**
     * The two players switch colors. If the engine played black it now plays white and vice-versa.
     */
    public void switchColors() {
        _playerColor = GoBoard.getOppositeColor(_playerColor);
    }


    /**
     * Returns a pretty ASCII board which shows the current position (gtp command "showboard").
     */
    public String getAsciiBoard() {
        return sendGtpCommand("showboard");
    }


    /**
     * Gets the player color.
     */
    public byte getPlayerColor() {
        return _playerColor;
    }

    /**
     * Gets the engine color.
     */
    public byte getBotColor() {
        return GoBoard.getOppositeColor(_playerColor);
    }

    /**
     * Gets the board size used for this game.
     *
     * @return The board size.
     */
    public int getBoardSize() {
        return _boardSize;
    }


    /**
     * Gets the underlying game used by the engine.
     */
    public GoGame getGame() {
        return _game;
    }

    /**
     * Returns true if the specified response to a GTP command indicates a success.
     */
    public boolean cmdSuccess(String response) {
        return response.charAt(0) == '=';
    }

    /**
     * Converts standard coordinates into their representation in the GTP protocol.
     * Special cases are : (-1, -1) = pass, (-3, -3) = resignation.
     *
     * @param coords The coordinates to convert.
     * @return The string representation.
     */
    protected String _point2str(Coords coords) {
        return _point2str(coords.x, coords.y);
    }

    /**
     * Converts standard coordinates into their representation in the GTP protocol.
     * Special cases are : (-1, -1) = pass, (-3, -3) = resignation.
     *
     * @return The string representation.
     */
    protected String _point2str(int x, int y) {
        if (x == -1 && y == -1)
            return "pass";
        else if (x == -3 && y == -3)
            return "resign";
        else
            return String.valueOf(_BOARD_LETTERS.charAt(x)) + (_boardSize - y);
    }

    /**
     * Converts GTP coordinates (i.e. "C5", "J12", ...) into their standard representation.
     * A pass will be returned as (-1, -1), a resignation as (-3, -3).
     *
     * @param coords
     * @return The converted coordinates, or null if they were invalid.
     */
    protected Coords _str2point(String coords) {
        if (coords.equalsIgnoreCase("pass"))
            return new Coords(-1, -1);
        else if (coords.equalsIgnoreCase("resign"))
            return new Coords(-3, -3);
        else {
            try {
                return new Coords(
                        _BOARD_LETTERS.indexOf(coords.charAt(0)),
                        _boardSize - Integer.parseInt(coords.substring(1).trim()));
            }
            catch (NumberFormatException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    protected void _gtpSendKomi(float komi) {

    }

    protected String _getColorString(byte color) {
        return (color == GoBoard.WHITE ? _WHITE_NAME : _BLACK_NAME);
    }

    protected String _getPlayerColorString() {
        return (_playerColor == GoBoard.WHITE ? _WHITE_NAME : _BLACK_NAME);
    }

    protected String _getBotColorString() {
        return (_playerColor == GoBoard.WHITE ? _BLACK_NAME : _WHITE_NAME);
    }
}
