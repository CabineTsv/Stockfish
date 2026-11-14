package dev.stuckfih.chess;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import dev.stuckfih.chess.core.Board;
import dev.stuckfih.chess.core.Game;
import dev.stuckfih.chess.core.Move;

/** Play chess against the bundled Stockfish engine. */
public final class MainActivity extends Activity implements EngineSession.Callbacks, BoardView.Listener {

    private static final int BG = 0xFF1B1B1F;
    private static final int PANEL = 0xFF2B2B33;
    private static final int TEXT = 0xFFEDEDED;
    private static final int SUBTLE = 0xFFA6A6B0;
    private static final int MAX_ENGINE_RESTARTS = 2;

    private Settings settings;
    private Game game = new Game();
    private boolean humanWhite = true;
    private boolean gameOverShown;

    private EngineSession engine;
    private String engineName = "Stockfish";
    private String engineError;
    private int engineRestarts;

    private BoardView boardView;
    private EvalBar evalBar;
    private TextView statusView;
    private TextView detailView;
    private TextView movesView;
    private ScrollView movesScroll;

    /** Token of the engine move we are waiting for, or -1. */
    private int playToken = -1;
    /** Token of a hint search, or -1. */
    private int hintToken = -1;
    private boolean searchRootWhite = true;
    private int plyAtRequest;
    private String evalText = "";
    private int evalDepth;
    private long evalNps;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        buildUi();
        restoreGame();
        refreshAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        engineRestarts = 0;
        startEngine();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveGame();
        stopEngine();
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(TEXT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        statusView = label(17, TEXT, Typeface.DEFAULT_BOLD);
        statusView.setPadding(pad, dp(10), pad, dp(2));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        detailView = label(12, SUBTLE, Typeface.DEFAULT);
        detailView.setPadding(pad, 0, pad, dp(8));
        root.addView(detailView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        evalBar = new EvalBar(this);
        LinearLayout.LayoutParams evalLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        evalLp.setMargins(dp(4), 0, dp(4), dp(6));
        root.addView(evalBar, evalLp);

        boardView = new BoardView(this);
        boardView.setListener(this);
        LinearLayout.LayoutParams boardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        boardLp.setMargins(dp(4), 0, dp(4), 0);
        root.addView(boardView, boardLp);

        movesScroll = new ScrollView(this);
        movesView = label(14, TEXT, Typeface.MONOSPACE);
        movesView.setPadding(0, 0, 0, dp(4));
        movesScroll.addView(movesView);
        LinearLayout.LayoutParams movesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        movesLp.setMargins(pad, dp(8), pad, 0);
        root.addView(movesScroll, movesLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(5), dp(6), dp(5), dp(8));
        buttons.addView(button("New", v -> newGameDialog()));
        buttons.addView(button("Undo", v -> undo()));
        buttons.addView(button("Hint", v -> hint()));
        buttons.addView(button("Flip", v -> boardView.setFlipped(!boardView.isFlipped())));
        buttons.addView(button("Settings", v -> showSettings()));
        root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ------------------------------------------------------------------ game state

    private boolean engineUsable() {
        return engineError == null;
    }

    private boolean isEngineTurn() {
        return engineUsable() && !game.isOver() && game.current().whiteToMove() != humanWhite;
    }

    private boolean canHumanMove() {
        return !game.isOver() && !isEngineTurn();
    }

    private void refreshAll() {
        boardView.setPosition(game.current(), game.lastMove(), canHumanMove());
        evalBar.setVisibility(settings.showEval() ? View.VISIBLE : View.INVISIBLE);
        updateMoves();
        updateStatus();
        updateDetail();
    }

    private void afterMove() {
        boardView.setPosition(game.current(), game.lastMove(), canHumanMove());
        updateMoves();
        updateStatus();
        if (game.isOver()) {
            showGameOver();
            return;
        }
        maybeEngineMove();
    }

    private void updateMoves() {
        String text = game.movetext();
        movesView.setText(text.isEmpty() ? "No moves yet." : text);
        movesScroll.post(new Runnable() {
            @Override
            public void run() {
                movesScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void updateStatus() {
        String s;
        if (game.isOver() || !engineUsable()) {
            s = game.statusText();
        } else if (hintToken != -1) {
            s = "Looking for a hint\u2026";
        } else if (isEngineTurn()) {
            s = "Stockfish is thinking\u2026";
        } else {
            s = "Your move" + (game.current().inCheck() ? " \u2014 check!" : "");
        }
        statusView.setText(s);
    }

    private void updateDetail() {
        StringBuilder sb = new StringBuilder();
        if (engineError != null) {
            sb.append(engineError);
        } else {
            sb.append(engine == null ? "Engine stopped" : engineName);
            if (evalDepth > 0 && settings.showEval()) {
                sb.append(" \u00b7 depth ").append(evalDepth).append(" \u00b7 ").append(evalText);
                if (evalNps > 0) sb.append(" \u00b7 ").append(formatNps(evalNps));
            }
            sb.append(" \u00b7 ").append(settings.threads()).append(settings.threads() == 1 ? " thread \u00b7 " : " threads \u00b7 ")
                    .append(settings.hashMb()).append(" MB");
        }
        detailView.setText(sb.toString());
    }

    private static String formatNps(long nps) {
        if (nps >= 1_000_000) return String.format(Locale.US, "%.1f Mn/s", nps / 1e6);
        return String.format(Locale.US, "%d kn/s", nps / 1000);
    }

    private void resetEval() {
        evalText = "";
        evalDepth = 0;
        evalNps = 0;
        evalBar.setWhiteShare(0.5f);
    }

    // ------------------------------------------------------------------ engine control

    private Map<String, String> engineOptions() {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("Threads", String.valueOf(settings.threads()));
        o.put("Hash", String.valueOf(settings.hashMb()));
        o.put("Move Overhead", "50");
        o.put("UCI_ShowWDL", "true");
        int elo = settings.elo();
        if (elo > 0) {
            o.put("UCI_LimitStrength", "true");
            o.put("UCI_Elo", String.valueOf(elo));
        } else {
            o.put("UCI_LimitStrength", "false");
        }
        return o;
    }

    private void startEngine() {
        if (engine != null) return;
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libstockfish.so");
        if (!exe.isFile()) {
            engineError = "The engine (libstockfish.so) is not part of this build. "
                    + "Install the APK produced by the GitHub workflow.";
            refreshAll();
            return;
        }
        EngineSession session = new EngineSession(this);
        try {
            session.start(Arrays.asList(exe.getAbsolutePath()), engineOptions());
        } catch (IOException | RuntimeException e) {
            engineError = "Could not start the engine: " + e.getMessage();
            refreshAll();
            return;
        }
        engine = session;
        engineError = null;
        refreshAll();
        maybeEngineMove();
    }

    private void stopEngine() {
        playToken = -1;
        hintToken = -1;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }

    private void cancelSearches() {
        playToken = -1;
        hintToken = -1;
        if (engine != null) engine.cancel();
    }

    private void maybeEngineMove() {
        if (engine == null || !isEngineTurn() || playToken != -1) return;
        searchRootWhite = game.current().whiteToMove();
        plyAtRequest = game.plyCount();
        hintToken = -1;
        boardView.clearHint();
        playToken = engine.search(game.startFen(), game.uciMoves(), settings.moveTimeMs());
        updateStatus();
    }

    /** Re-sends the options and restarts the engine's move if it was thinking. */
    private void applyEngineSettings() {
        if (engine == null) return;
        boolean wasThinking = playToken != -1;
        cancelSearches();
        for (Map.Entry<String, String> e : engineOptions().entrySet()) engine.setOption(e.getKey(), e.getValue());
        if (wasThinking) maybeEngineMove();
        updateStatus();
        updateDetail();
    }

    // ------------------------------------------------------------------ EngineSession.Callbacks (reader thread)

    @Override
    public void onReady(final String name) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                engineName = name;
                updateDetail();
            }
        });
    }

    @Override
    public void onInfo(final EngineSession.SearchInfo info, final int token) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleInfo(info, token);
            }
        });
    }

    @Override
    public void onBestMove(final String move, final int token) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleBestMove(move, token);
            }
        });
    }

    @Override
    public void onFailure(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleEngineFailure(message);
            }
        });
    }

    // ------------------------------------------------------------------ engine callbacks (UI thread)

    private void handleInfo(EngineSession.SearchInfo info, int token) {
        if ((token != playToken && token != hintToken) || info.lowerBound || info.upperBound) return;
        float rootShare;
        String text;
        if (info.mate) {
            int whiteMate = searchRootWhite ? info.score : -info.score;
            text = (whiteMate > 0 ? "M" : "-M") + Math.abs(whiteMate);
            rootShare = info.score > 0 ? 1f : 0f;
        } else {
            int whiteCp = searchRootWhite ? info.score : -info.score;
            text = String.format(Locale.US, "%+.2f", whiteCp / 100.0);
            if (info.wdlWin >= 0) rootShare = (info.wdlWin + info.wdlDraw / 2f) / 1000f;
            else rootShare = (float) (1.0 / (1.0 + Math.exp(-info.score / 400.0)));
        }
        evalText = text;
        evalDepth = info.depth;
        evalNps = info.nps;
        evalBar.setWhiteShare(searchRootWhite ? rootShare : 1f - rootShare);
        updateDetail();
    }

    private void handleBestMove(String move, int token) {
        if (token == hintToken) {
            hintToken = -1;
            if (move != null && game.plyCount() == plyAtRequest) {
                Move m = game.current().parseUci(move);
                if (m != null) boardView.setHint(m);
            }
            updateStatus();
            return;
        }
        if (token != playToken) return;
        playToken = -1;
        if (move == null || game.plyCount() != plyAtRequest || !isEngineTurn()) {
            updateStatus();
            return;
        }
        Move m = game.current().parseUci(move);
        if (m == null || !game.play(m)) {
            engineError = "The engine returned an unusable move: " + move;
            refreshAll();
            return;
        }
        afterMove();
    }

    private void handleEngineFailure(String message) {
        if (engine == null) return; // we shut it down ourselves
        engine = null;
        playToken = -1;
        hintToken = -1;
        if (engineRestarts < MAX_ENGINE_RESTARTS) {
            engineRestarts++;
            startEngine();
        } else {
            engineError = message;
            refreshAll();
        }
    }

    // ------------------------------------------------------------------ BoardView.Listener

    @Override
    public void onMove(Move move) {
        if (game.isOver() || isEngineTurn()) return;
        cancelSearches();
        if (!game.play(move)) return;
        afterMove();
    }

    @Override
    public void onPromotion(final int from, final int to) {
        final int[] types = {Board.QUEEN, Board.ROOK, Board.BISHOP, Board.KNIGHT};
        new AlertDialog.Builder(this)
                .setTitle("Promote to")
                .setItems(new String[]{"Queen", "Rook", "Bishop", "Knight"}, (d, which) ->
                        onMove(new Move(from, to, types[which])))
                .show();
    }

    // ------------------------------------------------------------------ buttons and dialogs

    private void undo() {
        if (game.plyCount() == 0) return;
        cancelSearches();
        if (!engineUsable()) {
            game.undo();
        } else {
            do {
                game.undo();
            } while (game.plyCount() > 0 && game.current().whiteToMove() != humanWhite);
        }
        gameOverShown = false;
        resetEval();
        afterMove();
    }

    private void hint() {
        if (engine == null || game.isOver() || isEngineTurn()) {
            toast("Hints are available on your turn while the engine is running.");
            return;
        }
        cancelSearches();
        searchRootWhite = game.current().whiteToMove();
        plyAtRequest = game.plyCount();
        hintToken = engine.search(game.startFen(), game.uciMoves(), Math.max(1500, settings.moveTimeMs()));
        updateStatus();
    }

    private void newGameDialog() {
        new AlertDialog.Builder(this)
                .setTitle("New game \u2014 play as")
                .setItems(new String[]{"White", "Black", "Random"}, (d, which) ->
                        startNewGame(which == 0 || (which == 2 && new Random().nextBoolean())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startNewGame(boolean white) {
        cancelSearches();
        humanWhite = white;
        game = new Game();
        gameOverShown = false;
        resetEval();
        boardView.setFlipped(!white);
        if (engine != null) engine.newGame();
        afterMove();
    }

    private void showGameOver() {
        if (gameOverShown) return;
        gameOverShown = true;
        new AlertDialog.Builder(this)
                .setTitle(game.statusText())
                .setPositiveButton("New game", (d, w) -> newGameDialog())
                .setNegativeButton("Close", null)
                .show();
    }

    private interface IndexCallback {
        void run(int index);
    }

    private void choose(String title, String[] labels, int checked, final IndexCallback callback) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    callback.run(which);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSettings() {
        int threads = settings.threadsSetting();
        int hash = settings.hashSetting();
        String[] items = {
                "Opponent strength: " + Settings.eloLabel(settings.eloIndex()),
                "Time per move: " + Settings.timeLabel(settings.moveTimeMs()),
                "Engine threads: " + (threads == 0 ? "Auto (" + settings.autoThreads() + ")" : String.valueOf(threads)),
                "Hash memory: " + (hash == 0 ? "Auto (" + settings.autoHashMb() + " MB)" : hash + " MB"),
                "Evaluation bar: " + (settings.showEval() ? "On" : "Off"),
                "Share game (PGN)",
                "Copy position (FEN)",
                "About",
        };
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            chooseStrength();
                            break;
                        case 1:
                            chooseTime();
                            break;
                        case 2:
                            chooseThreads();
                            break;
                        case 3:
                            chooseHash();
                            break;
                        case 4:
                            settings.setShowEval(!settings.showEval());
                            evalBar.setVisibility(settings.showEval() ? View.VISIBLE : View.INVISIBLE);
                            updateDetail();
                            showSettings();
                            break;
                        case 5:
                            sharePgn();
                            break;
                        case 6:
                            copyFen();
                            break;
                        default:
                            showAbout();
                            break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void chooseStrength() {
        String[] labels = new String[Settings.ELO_LEVELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Settings.eloLabel(i);
        choose("Opponent strength", labels, settings.eloIndex(), i -> {
            settings.setEloIndex(i);
            applyEngineSettings();
            showSettings();
        });
    }

    private void chooseTime() {
        String[] labels = new String[Settings.MOVE_TIMES_MS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Settings.timeLabel(Settings.MOVE_TIMES_MS[i]);
        choose("Time per move", labels, settings.moveTimeIndex(), i -> {
            settings.setMoveTimeIndex(i);
            showSettings();
        });
    }

    private void chooseThreads() {
        String[] labels = new String[Settings.MAX_THREADS + 1];
        labels[0] = "Auto (" + settings.autoThreads() + ")";
        for (int i = 1; i <= Settings.MAX_THREADS; i++) labels[i] = String.valueOf(i);
        choose("Engine threads", labels, settings.threadsSetting(), i -> {
            settings.setThreadsSetting(i);
            applyEngineSettings();
            showSettings();
        });
    }

    private void chooseHash() {
        final int[] values = new int[Settings.HASH_CHOICES_MB.length + 1];
        String[] labels = new String[values.length];
        labels[0] = "Auto (" + settings.autoHashMb() + " MB)";
        int checked = 0;
        for (int i = 1; i < values.length; i++) {
            values[i] = Settings.HASH_CHOICES_MB[i - 1];
            labels[i] = values[i] + " MB";
            if (values[i] == settings.hashSetting()) checked = i;
        }
        choose("Hash memory", labels, checked, i -> {
            settings.setHashSetting(values[i]);
            applyEngineSettings();
            showSettings();
        });
    }

    private void sharePgn() {
        String me = "You";
        String pgn = game.toPgn(humanWhite ? me : "Stockfish", humanWhite ? "Stockfish" : me);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, pgn);
        startActivity(Intent.createChooser(send, "Share game"));
    }

    private void copyFen() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("FEN", game.current().toFen()));
            toast("Position copied");
        }
    }

    private void showAbout() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // leave blank
        }
        String msg = "App version " + version + "\nEngine: " + engineName
                + "\nThreads: " + settings.threads() + "   Hash: " + settings.hashMb() + " MB\n\n"
                + "Stockfish is free software licensed under the GNU GPL v3; its source code is available at "
                + "https://github.com/official-stockfish/Stockfish.\n\n"
                + "Chess piece artwork by Colin M.L. Burnett (GPL v2 or later).";
        new AlertDialog.Builder(this).setTitle("About").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ persistence

    private void saveGame() {
        SharedPreferences.Editor e = settings.raw().edit();
        StringBuilder sb = new StringBuilder();
        for (String m : game.uciMoves()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m);
        }
        e.putString("game_start", game.startFen());
        e.putString("game_moves", sb.toString());
        e.putBoolean("human_white", humanWhite);
        e.putBoolean("flipped", boardView.isFlipped());
        e.apply();
    }

    private void restoreGame() {
        SharedPreferences p = settings.raw();
        String start = p.getString("game_start", Board.START_FEN);
        String moves = p.getString("game_moves", "");
        List<String> list = moves.isEmpty() ? new ArrayList<String>() : Arrays.asList(moves.split(" "));
        try {
            game = Game.fromMoves(start, list);
        } catch (IllegalArgumentException e) {
            game = new Game();
        }
        humanWhite = p.getBoolean("human_white", true);
        boardView.setFlipped(p.getBoolean("flipped", !humanWhite));
        gameOverShown = game.isOver();
    }
}
