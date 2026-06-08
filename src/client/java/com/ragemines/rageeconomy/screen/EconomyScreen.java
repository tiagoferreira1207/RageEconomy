package com.ragemines.rageeconomy.screen;

import com.ragemines.rageeconomy.data.EconomyData;
import com.ragemines.rageeconomy.data.EconomyDataLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

public class EconomyScreen extends Screen {

    // ─── Colors (ARGB) ─────────────────────────────────────────────────────────
    private static final int C_BG         = 0xF0100808;
    private static final int C_BG2        = 0xFF0D0606;
    private static final int C_PANEL      = 0xFF180C0C;
    private static final int C_CARD       = 0xFF1E1010;
    private static final int C_BORDER     = 0x55C0392B;
    private static final int C_ACCENT     = 0xFFE74C3C;
    private static final int C_ACCENT2    = 0xFFFF9448;
    private static final int C_ACCENT3    = 0xFF4FC3FF;
    private static final int C_TEXT       = 0xFFEEEEEE;
    private static final int C_TEXT_DIM   = 0xFF888888;
    private static final int C_TEXT_GOLD  = 0xFFFFD700;
    private static final int C_TAB_ACT    = 0xFF2A1010;
    private static final int C_TAB_INACT  = 0xFF140A0A;
    private static final int C_BTN        = 0xFF1E1010;
    private static final int C_BTN_ACT    = 0xFF3A1010;
    private static final int C_HIGHLIGHT  = 0x33E74C3C;
    private static final int C_SEP        = 0x33FFFFFF;

    // ─── Tabs ──────────────────────────────────────────────────────────────────
    private static final int TAB_DASHBOARD  = 0;
    private static final int TAB_ITEM_TREND = 1;
    private static final int TAB_WORLDS     = 2;

    private static final List<String> WORLD_ORDER = Arrays.asList(
        "Overworld", "Cave", "Sakura", "Iceworld", "Inferno",
        "Aether", "Oblivion", "Ocean", "Desert", "General"
    );

    // ─── Data state ────────────────────────────────────────────────────────────
    private EconomyData data = null;
    private boolean loading  = true;
    private String  errorMsg = null;

    // ─── Tab state ─────────────────────────────────────────────────────────────
    private int activeTab = TAB_DASHBOARD;

    // Dashboard
    private String dashDays   = "14";
    private int    dashButtonY = -1; // Y of time-range buttons, set during drawDashboard

    // Item Trend
    private TextFieldWidget itemSearchField;
    private TextFieldWidget startDateField;
    private TextFieldWidget endDateField;
    private boolean         includeTrades   = false;
    private String          selectedItem    = null;
    private List<String>    allItemNames    = new ArrayList<>();
    private List<String>    filteredItems   = new ArrayList<>();
    private boolean         dropdownOpen    = false;
    private int             dropdownScroll  = 0;
    private boolean         updatingSearchField = false; // guard against recursive onItemSearch
    private boolean         updatingDateFields  = false; // guard against triple runLookup on preset click
    private String          itemTrendDays       = "30"; // active date-preset for highlight
    private ItemStats       currentStats    = null;
    private List<WeekPt>    currentSeries   = null;

    // Worlds
    private String          worldsDays      = "30";
    private boolean         worldsTrades    = false;
    private TextFieldWidget worldSearch;
    private double          worldsScroll    = 0;
    private int             worldsTotalH    = 0;
    private Map<String, List<WorldItem>> worldBuckets = new LinkedHashMap<>();

    // ─── Layout ────────────────────────────────────────────────────────────────
    // Set in init()
    private int px, py, pw = 640, ph = 402;
    private int contentY;
    private int contentH;

    // ─── Inner data types ──────────────────────────────────────────────────────
    private static class ItemStats {
        int    count, uniqueSellers;
        long   totalAmount;
        double totalMoney, q1, median, q3, min, max;
    }

    private static class WeekPt {
        String date;
        double median;
        int    n;
    }

    private static class WorldItem {
        String name;
        double median;
        long   volume;
        int    tx;
    }

    // ══════════════════════════════════════════════════════════════════════════
    public EconomyScreen() {
        super(Text.literal("RageEconomy"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Override
    protected void init() {
        super.init();
        pw = Math.min(640, width - 20);
        ph = Math.min(402, height - 20);
        px = (width  - pw) / 2;
        py = (height - ph) / 2;
        contentY = py + 47;
        contentH = ph - 47;

        // ── Tab buttons ──────────────────────────────────────────────────────
        String[] labels = {"Dashboard", "Item Trend", "Prices by World"};
        int[] tabWidths  = {96, 90, 120};
        int tabX = px + 4; // accumulate x instead of i*(tw+4) which used wrong step
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            int tw = tabWidths[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]), btn -> switchTab(tab))
                .dimensions(tabX, py + 27, tw, 18)
                .build());
            tabX += tw + 4;
        }

        // ── Close button ─────────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(Text.literal("X"), btn -> close())
            .dimensions(px + pw - 18, py + 4, 14, 14)
            .build());

        // ── Item trend widgets ────────────────────────────────────────────────
        int fy = contentY + 5;
        itemSearchField = new TextFieldWidget(textRenderer, px + 64, fy, 148, 14, Text.literal(""));
        itemSearchField.setMaxLength(64);
        itemSearchField.setPlaceholder(Text.literal("Type to search..."));
        itemSearchField.setChangedListener(s -> onItemSearch(s));

        startDateField = new TextFieldWidget(textRenderer, px + 326, fy, 76, 14, Text.literal(""));
        startDateField.setMaxLength(10);
        startDateField.setPlaceholder(Text.literal("YYYY-MM-DD"));
        startDateField.setChangedListener(s -> { if (!updatingDateFields) runLookup(); });

        endDateField = new TextFieldWidget(textRenderer, px + 416, fy, 76, 14, Text.literal(""));
        endDateField.setMaxLength(10);
        endDateField.setPlaceholder(Text.literal("YYYY-MM-DD"));
        endDateField.setChangedListener(s -> { if (!updatingDateFields) runLookup(); });

        // ── Worlds search widget ──────────────────────────────────────────────
        worldSearch = new TextFieldWidget(textRenderer, px + pw - 128, contentY + 5, 120, 14, Text.literal(""));
        worldSearch.setMaxLength(64);
        worldSearch.setPlaceholder(Text.literal("Search items..."));
        worldSearch.setChangedListener(s -> { worldsScroll = 0; recomputeWorldBuckets(); });

        // Always register all text-field widgets; visibility is toggled each frame
        addDrawableChild(itemSearchField);
        addDrawableChild(startDateField);
        addDrawableChild(endDateField);
        addDrawableChild(worldSearch);

        // Start loading data
        if (data == null) {
            loading = true;
            EconomyDataLoader.load().thenAccept(result -> {
                MinecraftClient.getInstance().execute(() -> {
                    data = result;
                    if (data != null && data.items != null) {
                        allItemNames = data.items.stream().map(it -> it.name).collect(Collectors.toList());
                        filteredItems = new ArrayList<>(allItemNames);
                        if (data.generated_at != null) {
                            endDateField.setText(data.generated_at);
                            startDateField.setText(subtractDays(data.generated_at, 29));
                        }
                        recomputeWorldBuckets();
                    }
                    loading = false;
                });
            }).exceptionally(e -> {
                MinecraftClient.getInstance().execute(() -> {
                    errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    loading  = false;
                });
                return null;
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Dark background overlay
        ctx.fill(0, 0, width, height, 0xBB000000);

        // Panel
        ctx.fill(px, py, px + pw, py + ph, C_BG);
        drawBorder(ctx, px, py, pw, ph, C_BORDER);

        // Header bar
        ctx.fill(px, py, px + pw, py + 26, C_BG2);
        ctx.drawText(textRenderer, "  RageEconomy Economy Viewer", px + 4, py + 8, C_ACCENT, false);
        if (data != null && data.generated_at != null) {
            String gen = "Data: " + data.generated_at;
            ctx.drawText(textRenderer, gen, px + pw - textRenderer.getWidth(gen) - 22, py + 8, C_TEXT_DIM, false);
        }

        // Tab bar background
        ctx.fill(px, py + 26, px + pw, contentY, C_BG2);
        // Active tab highlight under the active tab button area
        int[] tabWidths = {96, 90, 120};
        int tabHlX = px + 4;
        for (int i = 0; i < 3; i++) {
            int tw = tabWidths[i];
            if (i == activeTab) {
                ctx.fill(tabHlX, py + 27, tabHlX + tw, contentY, C_TAB_ACT);
            }
            tabHlX += tw + 4;
        }

        // Content area
        ctx.fill(px, contentY, px + pw, py + ph, C_PANEL);

        // Always hide text-field widgets that belong to other tabs — must happen
        // before super.render() on EVERY path, including loading/error states.
        boolean isItemTab   = activeTab == TAB_ITEM_TREND;
        boolean isWorldsTab = activeTab == TAB_WORLDS;
        itemSearchField.visible = isItemTab;
        startDateField.visible  = isItemTab;
        endDateField.visible    = isItemTab;
        worldSearch.visible     = isWorldsTab;

        // Loading / error state
        if (loading) {
            String msg = "Loading economy data...";
            ctx.drawText(textRenderer, msg, px + pw / 2 - textRenderer.getWidth(msg) / 2, py + ph / 2, C_TEXT_DIM, false);
            super.render(ctx, mx, my, delta);
            return;
        }
        if (errorMsg != null) {
            String msg = "Error: " + errorMsg;
            ctx.drawText(textRenderer, msg, px + 10, py + ph / 2, 0xFFFF5555, false);
            super.render(ctx, mx, my, delta);
            return;
        }
        if (data == null) {
            super.render(ctx, mx, my, delta);
            return;
        }

        // Draw current tab
        switch (activeTab) {
            case TAB_DASHBOARD  -> drawDashboard(ctx, mx, my);
            case TAB_ITEM_TREND -> drawItemTrend(ctx, mx, my);
            case TAB_WORLDS     -> drawWorldsTab(ctx, mx, my);
        }

        // Draw child widgets (buttons, text fields)
        super.render(ctx, mx, my, delta);

        // Dropdown on top of everything
        if (activeTab == TAB_ITEM_TREND && dropdownOpen && !filteredItems.isEmpty()) {
            drawDropdown(ctx, mx, my);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DASHBOARD TAB
    // ══════════════════════════════════════════════════════════════════════════
    private void drawDashboard(DrawContext ctx, int mx, int my) {
        EconomyData.Dashboard dash = data.dashboard;
        int cy = contentY + 6;
        int cw = pw - 12;
        int cx = px + 6;

        // ── 3 stat cards ─────────────────────────────────────────────────────
        int cardW = (cw - 12) / 3;
        String[][] cards = {
            {"AH Sales",  fmt(dash.total_ah_sales),  "All time"},
            {"Orders",    fmt(dash.total_orders),     "All time"},
            {"Trades",    fmt(dash.total_trades),     "All time"}
        };
        int[] cardColors = {C_ACCENT, C_ACCENT2, C_ACCENT3};
        for (int i = 0; i < 3; i++) {
            int cx2 = cx + i * (cardW + 6);
            drawCard(ctx, cx2, cy, cardW, 44, cardColors[i]);
            ctx.drawText(textRenderer, cards[i][0], cx2 + 6, cy + 5, C_TEXT_DIM, false);
            ctx.drawText(textRenderer, cards[i][1], cx2 + 6, cy + 17, cardColors[i], false);
            ctx.drawText(textRenderer, cards[i][2], cx2 + 6, cy + 32, C_TEXT_DIM, false);
        }
        cy += 52;

        // ── World volumes ─────────────────────────────────────────────────────
        ctx.drawText(textRenderer, "Transaction Volume by World", cx, cy, C_TEXT, false);
        cy += 12;
        if (dash.world_volumes != null && !dash.world_volumes.isEmpty()) {
            int maxVol = dash.world_volumes.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            int barH = 8;
            int numColW  = 38; // reserved width for the volume number on the right
            int barMaxW  = cw - 80 - numColW - 4; // bars end before the number column
            for (String world : WORLD_ORDER) {
                Integer vol = dash.world_volumes.get(world);
                if (vol == null || vol == 0) continue;
                int barW = (int) ((double) vol / maxVol * barMaxW);
                ctx.drawText(textRenderer, world, cx, cy + 1, C_TEXT_DIM, false);
                ctx.fill(cx + 70, cy, cx + 70 + barW, cy + barH, C_ACCENT);
                ctx.fill(cx + 70 + barW, cy, cx + 70 + barMaxW, cy + barH, 0x22FFFFFF);
                String numStr = fmt(vol);
                int numX = cx + cw - textRenderer.getWidth(numStr); // right-align within panel
                ctx.drawText(textRenderer, numStr, numX, cy + 1, C_TEXT_DIM, false);
                cy += barH + 4;
            }
        }
        cy += 6;

        // ── Daily Activity chart ──────────────────────────────────────────────
        ctx.drawText(textRenderer, "Daily Activity", cx, cy, C_TEXT, false);
        dashButtonY = cy; // record for click detection
        // Time range buttons
        String[] dOpts = {"7", "14", "30", "90", "all"};
        String[] dLabels = {"7d", "14d", "30d", "90d", "All"};
        int bx = cx + 100;
        for (int i = 0; i < dOpts.length; i++) {
            boolean act = dashDays.equals(dOpts[i]);
            int bw = textRenderer.getWidth(dLabels[i]) + 8;
            ctx.fill(bx, cy - 1, bx + bw, cy + 11, act ? C_BTN_ACT : C_BTN);
            drawBorder(ctx, bx, cy - 1, bw, 12, act ? C_ACCENT : C_BORDER);
            ctx.drawText(textRenderer, dLabels[i], bx + 4, cy + 1, act ? C_ACCENT : C_TEXT_DIM, false);
            bx += bw + 3;
        }
        cy += 14;

        // Chart area
        int chartH  = py + ph - cy - 8;
        int chartW  = cw;
        if (chartH < 30 || dash.daily == null || dash.daily.isEmpty()) return;

        List<EconomyData.DailyEntry> days = filterDashDays(dash.daily, dashDays);
        if (days.isEmpty()) return;

        // Chart background
        ctx.fill(cx, cy, cx + chartW, cy + chartH, C_CARD);
        drawBorder(ctx, cx, cy, chartW, chartH, C_BORDER);

        // Y axis max
        int maxY = 1;
        for (EconomyData.DailyEntry d : days) maxY = Math.max(maxY, d.ah + d.orders + d.trades);
        maxY = Math.max(maxY, 10);

        // Plot lines: AH, Orders, Trades
        int innerL = cx + 4, innerT = cy + 4, innerW = chartW - 8, innerH = chartH - 16;
        int n = days.size();
        if (n < 2) return;

        ctx.enableScissor(cx, cy, cx + chartW, cy + chartH);

        int[][] series = new int[3][n];
        int[] seriesCols = {C_ACCENT, C_ACCENT2, C_ACCENT3};
        for (int i = 0; i < n; i++) {
            series[0][i] = days.get(i).ah;
            series[1][i] = days.get(i).orders;
            series[2][i] = days.get(i).trades;
        }
        for (int s = 0; s < 3; s++) {
            for (int i = 1; i < n; i++) {
                int x1 = innerL + (i - 1) * innerW / (n - 1);
                int y1 = innerT + innerH - series[s][i - 1] * innerH / maxY;
                int x2 = innerL + i * innerW / (n - 1);
                int y2 = innerT + innerH - series[s][i] * innerH / maxY;
                drawLine(ctx, x1, y1, x2, y2, seriesCols[s]);
            }
        }

        // X axis labels (first and last date)
        String dateFirst = days.get(0).date.substring(5);
        String dateLast  = days.get(n - 1).date.substring(5);
        ctx.drawText(textRenderer, dateFirst, innerL, cy + chartH - 10, C_TEXT_DIM, false);
        ctx.drawText(textRenderer, dateLast,  innerL + innerW - textRenderer.getWidth(dateLast), cy + chartH - 10, C_TEXT_DIM, false);

        // Legend
        String[] legends = {"AH", "Orders", "Trades"};
        int lx = cx + chartW - 6;
        for (int i = 2; i >= 0; i--) {
            String lbl = legends[i];
            int lw = textRenderer.getWidth(lbl) + 14;
            lx -= lw;
            ctx.fill(lx, cy + 3, lx + 8, cy + 9, seriesCols[i]);
            ctx.drawText(textRenderer, lbl, lx + 10, cy + 3, seriesCols[i], false);
            lx -= 4;
        }

        ctx.disableScissor();

        // Mouse hover: check if over time range buttons
        // (handled by mouseClicked below)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ITEM TREND TAB
    // ══════════════════════════════════════════════════════════════════════════
    private void drawItemTrend(DrawContext ctx, int mx, int my) {
        int cy = contentY + 5;
        int cx = px + 6;
        int cw = pw - 12;

        // ── Row 1: controls ──────────────────────────────────────────────────
        ctx.drawText(textRenderer, "Item:", cx, cy + 3, C_TEXT_DIM, false);
        // itemSearchField drawn by super.render

        // Clear button
        if (selectedItem != null || !itemSearchField.getText().isEmpty()) {
            ctx.fill(cx + 215, cy, cx + 228, cy + 16, C_BTN);
            ctx.drawText(textRenderer, "x", cx + 218, cy + 3, C_TEXT_DIM, false);
        }

        // Labels positioned flush-right just before each field so they're never clipped
        int fromLblX = startDateField.getX() - textRenderer.getWidth("From:") - 3;
        int toLblX   = endDateField.getX()   - textRenderer.getWidth("To:")   - 3;
        ctx.drawText(textRenderer, "From:", fromLblX, cy + 3, C_TEXT_DIM, false);
        ctx.drawText(textRenderer, "To:",   toLblX,   cy + 3, C_TEXT_DIM, false);

        // Include trades checkbox — placed after the end-date field
        boolean chk = includeTrades;
        int cbx = endDateField.getX() + endDateField.getWidth() + 8, cby = cy + 2;
        ctx.fill(cbx, cby, cbx + 9, cby + 9, C_BTN);
        drawBorder(ctx, cbx, cby, 9, 9, C_BORDER);
        if (chk) ctx.drawText(textRenderer, "x", cbx + 1, cby, C_ACCENT, false);
        ctx.drawText(textRenderer, "Trades", cbx + 12, cy + 3, C_TEXT_DIM, false);

        cy += 20;

        // ── Row 2: date presets ──────────────────────────────────────────────
        String[] dOpts   = {"7", "14", "30", "all"};
        String[] dLabels = {"7d", "14d", "30d", "All"};
        int bx = cx;
        for (int i = 0; i < dOpts.length; i++) {
            boolean act = itemTrendDays.equals(dOpts[i]);
            int bw = textRenderer.getWidth(dLabels[i]) + 8;
            ctx.fill(bx, cy, bx + bw, cy + 12, act ? C_BTN_ACT : C_BTN);
            drawBorder(ctx, bx, cy, bw, 12, act ? C_ACCENT : C_BORDER);
            ctx.drawText(textRenderer, dLabels[i], bx + 4, cy + 2, act ? C_ACCENT : C_TEXT_DIM, false);
            bx += bw + 4;
        }
        cy += 16;

        // ── Stats panel ──────────────────────────────────────────────────────
        if (selectedItem == null || currentStats == null) {
            String hint = selectedItem == null
                ? "Select an item to view its market data."
                : "No sales in the selected range.";
            ctx.drawText(textRenderer, hint, cx + cw / 2 - textRenderer.getWidth(hint) / 2, cy + 10, C_TEXT_DIM, false);
            return;
        }

        ItemStats st = currentStats;
        // Stats cards: 4 across
        int cardW = (cw - 9) / 4;
        String[][] cards = {
            {"Volume",       fmt(st.totalAmount)},
            {"Money",        "$" + fmtMoney(st.totalMoney)},
            {"Transactions", fmt(st.count)},
            {"Sellers",      fmt(st.uniqueSellers)}
        };
        for (int i = 0; i < 4; i++) {
            int cx2 = cx + i * (cardW + 3);
            drawCard(ctx, cx2, cy, cardW, 30, C_ACCENT);
            ctx.drawText(textRenderer, cards[i][0], cx2 + 4, cy + 3,  C_TEXT_DIM, false);
            ctx.drawText(textRenderer, cards[i][1], cx2 + 4, cy + 16, C_TEXT, false);
        }
        cy += 34;

        // Q1 / Median / Q3
        String[][] quartile = {
            {"Q1",     fmtPpi(st.q1)},
            {"Median", fmtPpi(st.median)},
            {"Q3",     fmtPpi(st.q3)}
        };
        int qW = (cw - 6) / 3;
        for (int i = 0; i < 3; i++) {
            int cx2 = cx + i * (qW + 3);
            drawCard(ctx, cx2, cy, qW, 30, C_ACCENT2);
            ctx.drawText(textRenderer, quartile[i][0], cx2 + 4, cy + 3,  C_TEXT_DIM, false);
            ctx.drawText(textRenderer, "$" + quartile[i][1], cx2 + 4, cy + 16, C_TEXT_GOLD, false);
        }
        cy += 34;

        // ── Item name ─────────────────────────────────────────────────────────
        ctx.drawText(textRenderer, "Weekly Median Price  —  " + selectedItem, cx, cy, C_TEXT, false);
        cy += 12;

        // ── Chart ─────────────────────────────────────────────────────────────
        int chartH = py + ph - cy - 8;
        int chartW = cw;
        if (chartH < 40 || currentSeries == null || currentSeries.size() < 2) {
            if (currentSeries != null && currentSeries.size() < 2) {
                ctx.drawText(textRenderer, "Not enough data for a chart.", cx, cy + 10, C_TEXT_DIM, false);
            }
            return;
        }

        int n = currentSeries.size();
        double minVal = currentSeries.stream().mapToDouble(p -> p.median).min().orElse(0);
        double maxVal = currentSeries.stream().mapToDouble(p -> p.median).max().orElse(1);
        if (maxVal == minVal) { maxVal = minVal + 1; }
        double range = maxVal - minVal;

        // Reserve left margin for Y-axis labels so they don't overlap the chart line
        String yTopLabel = "$" + fmtPpi(maxVal);
        String yBotLabel = "$" + fmtPpi(minVal);
        int yLabelW = Math.max(textRenderer.getWidth(yTopLabel), textRenderer.getWidth(yBotLabel)) + 4;

        ctx.fill(cx, cy, cx + chartW, cy + chartH, C_CARD);
        drawBorder(ctx, cx, cy, chartW, chartH, C_BORDER);

        // Y-axis labels drawn outside the scissor region (left margin), no overlap with chart
        ctx.drawText(textRenderer, yTopLabel, cx + 2, cy + 4,           C_TEXT_DIM, false);
        ctx.drawText(textRenderer, yBotLabel, cx + 2, cy + chartH - 16, C_TEXT_DIM, false);

        ctx.enableScissor(cx, cy, cx + chartW, cy + chartH);

        // innerL pushed right by the Y-label margin so chart line never touches labels
        int innerL = cx + yLabelW, innerT = cy + 4, innerW = chartW - yLabelW - 8, innerH = chartH - 16;

        for (int i = 1; i < n; i++) {
            int x1 = innerL + (i - 1) * innerW / (n - 1);
            int y1 = innerT + innerH - (int) ((currentSeries.get(i - 1).median - minVal) / range * innerH);
            int x2 = innerL + i * innerW / (n - 1);
            int y2 = innerT + innerH - (int) ((currentSeries.get(i).median - minVal) / range * innerH);
            drawLine(ctx, x1, y1, x2, y2, C_ACCENT);
            // Dot at each point
            ctx.fill(x2 - 1, y2 - 1, x2 + 2, y2 + 2, C_ACCENT);
        }
        // First dot
        {
            int x0 = innerL;
            int y0 = innerT + innerH - (int) ((currentSeries.get(0).median - minVal) / range * innerH);
            ctx.fill(x0 - 1, y0 - 1, x0 + 2, y0 + 2, C_ACCENT);
        }

        // X-axis labels (dates)
        String sFirst = currentSeries.get(0).date.substring(5);
        String sLast  = currentSeries.get(n - 1).date.substring(5);
        ctx.drawText(textRenderer, sFirst, innerL, cy + chartH - 10, C_TEXT_DIM, false);
        ctx.drawText(textRenderer, sLast, innerL + innerW - textRenderer.getWidth(sLast), cy + chartH - 10, C_TEXT_DIM, false);

        // Mouse hover tooltip
        if (mx >= cx && mx <= cx + chartW && my >= cy && my <= cy + chartH && n >= 2) {
            int relX = mx - innerL;
            int idx  = Math.round((float) relX * (n - 1) / innerW);
            idx = Math.max(0, Math.min(n - 1, idx));
            WeekPt pt = currentSeries.get(idx);
            int dotX = innerL + idx * innerW / (n - 1);
            int dotY = innerT + innerH - (int) ((pt.median - minVal) / range * innerH);
            // Vertical line
            ctx.fill(dotX, innerT, dotX + 1, innerT + innerH, 0x44FFFFFF);
            // Tooltip
            String tip1 = "Week of " + pt.date;
            String tip2 = "Median: $" + fmtPpi(pt.median) + "  (" + pt.n + " sales)";
            int tipW = Math.max(textRenderer.getWidth(tip1), textRenderer.getWidth(tip2)) + 8;
            int tipH = 24;
            int tipX = Math.min(dotX + 4, cx + chartW - tipW - 2);
            int tipY = Math.max(cy + 2, dotY - tipH - 4);
            ctx.fill(tipX, tipY, tipX + tipW, tipY + tipH, 0xEE150A0A);
            drawBorder(ctx, tipX, tipY, tipW, tipH, C_ACCENT);
            ctx.drawText(textRenderer, tip1, tipX + 4, tipY + 3,  C_TEXT_DIM, false);
            ctx.drawText(textRenderer, tip2, tipX + 4, tipY + 13, C_ACCENT, false);
        }

        ctx.disableScissor();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRICES BY WORLD TAB
    // ══════════════════════════════════════════════════════════════════════════
    private void drawWorldsTab(DrawContext ctx, int mx, int my) {
        int cy = contentY + 5;
        int cx = px + 6;
        int cw = pw - 12;

        // ── Controls row ─────────────────────────────────────────────────────
        String[] wOpts   = {"7", "30", "90", "all"};
        String[] wLabels = {"7d", "30d", "90d", "All"};
        int bx = cx;
        for (int i = 0; i < wOpts.length; i++) {
            boolean act = worldsDays.equals(wOpts[i]);
            int bw = textRenderer.getWidth(wLabels[i]) + 8;
            ctx.fill(bx, cy, bx + bw, cy + 13, act ? C_BTN_ACT : C_BTN);
            drawBorder(ctx, bx, cy, bw, 13, act ? C_ACCENT : C_BORDER);
            ctx.drawText(textRenderer, wLabels[i], bx + 4, cy + 3, act ? C_ACCENT : C_TEXT_DIM, false);
            bx += bw + 3;
        }

        // Include trades toggle
        int cbx = bx + 4, cby = cy + 2;
        ctx.fill(cbx, cby, cbx + 9, cby + 9, C_BTN);
        drawBorder(ctx, cbx, cby, 9, 9, C_BORDER);
        if (worldsTrades) ctx.drawText(textRenderer, "x", cbx + 1, cby, C_ACCENT, false);
        ctx.drawText(textRenderer, "Trades", cbx + 12, cy + 3, C_TEXT_DIM, false);

        // World search field rendered by super.render — label flush-left of the field
        int searchLblX = worldSearch.getX() - textRenderer.getWidth("Search:") - 3;
        ctx.drawText(textRenderer, "Search:", searchLblX, cy + 3, C_TEXT_DIM, false);
        cy += 20;

        // ── Scrollable world cards ────────────────────────────────────────────
        int scrollAreaTop = cy;
        int scrollAreaH   = py + ph - cy - 4;

        ctx.enableScissor(cx, scrollAreaTop, cx + cw, scrollAreaTop + scrollAreaH);

        int drawY = scrollAreaTop - (int) worldsScroll;
        String searchQ = worldSearch.getText().toLowerCase().trim();

        for (String world : WORLD_ORDER) {
            List<WorldItem> items = worldBuckets.get(world);
            if (items == null || items.isEmpty()) continue;

            // Filter by search
            List<WorldItem> shown = searchQ.isEmpty()
                ? items
                : items.stream().filter(wi -> wi.name.toLowerCase().contains(searchQ)).collect(Collectors.toList());
            if (shown.isEmpty()) continue;

            int rowH  = 10;
            int cardH = 24 + shown.size() * rowH + 4;

            if (drawY + cardH > scrollAreaTop && drawY < scrollAreaTop + scrollAreaH) {
                ctx.fill(cx, drawY, cx + cw, drawY + cardH, C_CARD);
                drawBorder(ctx, cx, drawY, cw, cardH, C_BORDER);

                // World header
                ctx.fill(cx, drawY, cx + cw, drawY + 16, C_BTN_ACT);
                ctx.drawText(textRenderer, world, cx + 6, drawY + 4, C_ACCENT, false);
                ctx.drawText(textRenderer, shown.size() + " items", cx + 80, drawY + 4, C_TEXT_DIM, false);

                // Table header
                int hy = drawY + 17;
                ctx.fill(cx, hy, cx + cw, hy + 10, 0x22FFFFFF);
                ctx.drawText(textRenderer, "Item",        cx + 4,        hy + 1, C_TEXT_DIM, false);
                ctx.drawText(textRenderer, "Median/item", cx + cw - 220, hy + 1, C_TEXT_DIM, false);
                ctx.drawText(textRenderer, "Volume",      cx + cw - 130, hy + 1, C_TEXT_DIM, false);
                ctx.drawText(textRenderer, "Tx",          cx + cw - 50,  hy + 1, C_TEXT_DIM, false);
                hy += 11;

                for (WorldItem wi : shown) {
                    boolean hover = mx >= cx && mx <= cx + cw && my >= hy && my < hy + rowH;
                    if (hover) ctx.fill(cx, hy, cx + cw, hy + rowH, C_HIGHLIGHT);
                    ctx.drawText(textRenderer, wi.name,        cx + 4,        hy + 1, C_TEXT, false);
                    ctx.drawText(textRenderer, "$" + fmtPpi(wi.median), cx + cw - 220, hy + 1, C_TEXT_GOLD, false);
                    ctx.drawText(textRenderer, fmt(wi.volume), cx + cw - 130, hy + 1, C_TEXT_DIM, false);
                    ctx.drawText(textRenderer, fmt(wi.tx),     cx + cw - 50,  hy + 1, C_TEXT_DIM, false);
                    hy += rowH;
                }
            }

            drawY += cardH + 4;
        }

        worldsTotalH = drawY - scrollAreaTop + (int) worldsScroll;
        ctx.disableScissor();

        // Scrollbar — drawn inside the right edge of the content area
        if (worldsTotalH > scrollAreaH) {
            int sbX  = cx + cw - 5; // 5 px wide, flush with right edge inside the panel
            int sbH  = scrollAreaH;
            int thumbH = Math.max(20, sbH * sbH / worldsTotalH);
            int thumbY = scrollAreaTop + (int) ((double) worldsScroll * (sbH - thumbH) / (worldsTotalH - scrollAreaH));
            ctx.fill(sbX, scrollAreaTop, sbX + 5, scrollAreaTop + sbH, 0x22FFFFFF);
            ctx.fill(sbX, thumbY, sbX + 5, thumbY + thumbH, C_BORDER);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MOUSE & KEYBOARD
    // ══════════════════════════════════════════════════════════════════════════
    // Minecraft 1.21.11: mouseClicked now receives a Click record
    @Override
    public boolean mouseClicked(Click click, boolean wasDragging) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        if (btn != 0) return super.mouseClicked(click, wasDragging);

        // Dropdown pick
        if (activeTab == TAB_ITEM_TREND && dropdownOpen) {
            int ddX = px + 64, ddY = contentY + 20;
            int ddW = 148, maxRows = Math.min(filteredItems.size() - dropdownScroll, 8);
            int ddH = maxRows * 11 + 2; // +2 matches the drawn height in drawDropdown
            if (mx >= ddX && mx <= ddX + ddW && my >= ddY && my <= ddY + ddH) {
                int idx = (int) ((my - ddY) / 11) + dropdownScroll;
                if (idx >= 0 && idx < filteredItems.size()) {
                    selectItem(filteredItems.get(idx));
                }
                return true;
            } else {
                dropdownOpen = false;
            }
        }

        int imx = (int) mx, imy = (int) my;

        // Dashboard time range buttons
        if (activeTab == TAB_DASHBOARD && data != null && dashButtonY >= 0) {
            String[] dOpts   = {"7", "14", "30", "90", "all"};
            String[] dLabels = {"7d", "14d", "30d", "90d", "All"};
            int cy = dashButtonY;
            int bx = px + 6 + 100;
            for (int i = 0; i < dOpts.length; i++) {
                int bw = textRenderer.getWidth(dLabels[i]) + 8;
                if (imx >= bx && imx <= bx + bw && imy >= cy - 1 && imy <= cy + 11) {
                    dashDays = dOpts[i];
                    return true;
                }
                bx += bw + 3;
            }
        }

        // Item Trend controls
        if (activeTab == TAB_ITEM_TREND) {
            int cy = contentY + 5;
            int cx = px + 6;

            // Clear button
            if (imx >= cx + 215 && imx <= cx + 228 && imy >= cy && imy <= cy + 16) {
                clearItem();
                return true;
            }

            // Include trades checkbox — position mirrors drawItemTrend
            int cbx = endDateField.getX() + endDateField.getWidth() + 8, cby = cy + 2;
            if (imx >= cbx && imx <= cbx + 9 && imy >= cby && imy <= cby + 9) {
                includeTrades = !includeTrades;
                runLookup();
                return true;
            }

            // Date presets
            cy += 20;
            String[] dOpts   = {"7", "14", "30", "all"};
            String[] dLabels = {"7d", "14d", "30d", "All"};
            int bx = cx;
            for (int i = 0; i < dOpts.length; i++) {
                int bw = textRenderer.getWidth(dLabels[i]) + 8;
                if (imx >= bx && imx <= bx + bw && imy >= cy && imy <= cy + 12 && data != null && data.generated_at != null) {
                    itemTrendDays = dOpts[i];
                    String end   = data.generated_at;
                    String start = dOpts[i].equals("all") ? (data.earliest_date != null ? data.earliest_date : end)
                        : subtractDays(end, Integer.parseInt(dOpts[i]) - 1);
                    // Guard prevents the two setText calls from each firing runLookup via onChanged
                    updatingDateFields = true;
                    startDateField.setText(start);
                    endDateField.setText(end);
                    updatingDateFields = false;
                    runLookup(); // single authoritative call
                    return true;
                }
                bx += bw + 4;
            }
        }

        // Worlds controls
        if (activeTab == TAB_WORLDS) {
            int cy = contentY + 5;
            int cx = px + 6;
            String[] wOpts   = {"7", "30", "90", "all"};
            String[] wLabels = {"7d", "30d", "90d", "All"};
            int bx = cx;
            for (int i = 0; i < wOpts.length; i++) {
                int bw = textRenderer.getWidth(wLabels[i]) + 8;
                if (imx >= bx && imx <= bx + bw && imy >= cy && imy <= cy + 13) {
                    worldsDays = wOpts[i];
                    worldsScroll = 0;
                    recomputeWorldBuckets();
                    return true;
                }
                bx += bw + 3;
            }
            // Include trades
            int cbx = bx + 4, cby = cy + 2;
            if (imx >= cbx && imx <= cbx + 9 && imy >= cby && imy <= cby + 9) {
                worldsTrades = !worldsTrades;
                worldsScroll = 0;
                recomputeWorldBuckets();
                return true;
            }
        }

        return super.mouseClicked(click, wasDragging);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (activeTab == TAB_WORLDS) {
            int scrollAreaH = py + ph - (contentY + 20) - 4;
            double maxScroll = Math.max(0, worldsTotalH - scrollAreaH);
            worldsScroll = Math.max(0, Math.min(maxScroll, worldsScroll - vAmt * 16));
            return true;
        }
        if (activeTab == TAB_ITEM_TREND && dropdownOpen) {
            int maxScroll = Math.max(0, filteredItems.size() - 8);
            dropdownScroll = (int) Math.max(0, Math.min(maxScroll, dropdownScroll - vAmt));
            return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    // Minecraft 1.21.11: keyPressed now receives a KeyInput record
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == 256) { // GLFW_KEY_ESCAPE
            if (dropdownOpen) { dropdownOpen = false; return true; }
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DROPDOWN
    // ══════════════════════════════════════════════════════════════════════════
    private void drawDropdown(DrawContext ctx, int mx, int my) {
        int ddX = px + 64, ddY = contentY + 20;
        int ddW = 148;
        int maxRows = Math.min(filteredItems.size() - dropdownScroll, 8);
        if (maxRows <= 0) return;
        int ddH = maxRows * 11 + 2;

        ctx.fill(ddX, ddY, ddX + ddW, ddY + ddH, 0xFF180C0C);
        drawBorder(ctx, ddX, ddY, ddW, ddH, C_ACCENT);

        ctx.enableScissor(ddX, ddY, ddX + ddW, ddY + ddH);
        for (int i = 0; i < maxRows; i++) {
            int idx = i + dropdownScroll;
            if (idx >= filteredItems.size()) break;
            String name = filteredItems.get(idx);
            int iy = ddY + 1 + i * 11;
            boolean hover = mx >= ddX && mx <= ddX + ddW && my >= iy && my < iy + 11;
            if (hover) ctx.fill(ddX, iy, ddX + ddW, iy + 11, C_HIGHLIGHT);
            ctx.drawText(textRenderer, name, ddX + 3, iy + 2, hover ? C_TEXT : C_TEXT_DIM, false);
        }
        ctx.disableScissor();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private void switchTab(int tab) {
        activeTab    = tab;
        dropdownOpen = false;
        setFocused(null);
    }

    private void onItemSearch(String text) {
        if (updatingSearchField) return; // prevent recursive loop when selectItem sets text
        String q = text.toLowerCase().trim();
        filteredItems = q.isEmpty()
            ? new ArrayList<>(allItemNames)
            : allItemNames.stream().filter(n -> n.toLowerCase().contains(q)).collect(Collectors.toList());
        dropdownOpen = true;
        dropdownScroll = 0;

        // If text matches exactly, auto-select
        if (allItemNames.contains(text)) {
            selectItem(text);
        } else {
            selectedItem = null;
            currentStats  = null;
            currentSeries = null;
        }
    }

    private void selectItem(String name) {
        selectedItem = name;
        updatingSearchField = true;
        itemSearchField.setText(name);
        updatingSearchField = false;
        dropdownOpen  = false;
        dropdownScroll = 0;
        runLookup();
    }

    private void clearItem() {
        selectedItem  = null;
        currentStats  = null;
        currentSeries = null;
        updatingSearchField = true;
        itemSearchField.setText("");
        updatingSearchField = false;
        dropdownOpen  = false;
    }

    private void runLookup() {
        if (data == null || data.items == null || selectedItem == null) return;
        EconomyData.Item item = data.items.stream()
            .filter(it -> it.name.equals(selectedItem))
            .findFirst().orElse(null);
        if (item == null) { currentStats = null; currentSeries = null; return; }

        String start = startDateField.getText().trim();
        String end   = endDateField.getText().trim();
        currentStats  = computeItemStats(item, start, end, includeTrades);
        currentSeries = computeWeeklySeries(item, start, end, includeTrades);
    }

    private ItemStats computeItemStats(EconomyData.Item item, String start, String end, boolean trades) {
        if (item.sales == null) return null;
        List<Double> ppis = new ArrayList<>();
        Set<String> sellers = new HashSet<>();
        long totalAmt = 0; double totalMoney = 0;

        for (EconomyData.Sale s : item.sales) {
            if (!trades && "trade".equals(s.s)) continue;
            if (!start.isEmpty() && s.d.compareTo(start) < 0) continue;
            if (!end.isEmpty()   && s.d.compareTo(end)   > 0) continue;
            ppis.add(s.ppi);
            sellers.add(s.u);
            totalAmt   += s.a;
            totalMoney += s.p;
        }
        if (ppis.isEmpty()) return null;
        double[] sorted = ppis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        ItemStats st = new ItemStats();
        st.count         = ppis.size();
        st.uniqueSellers = sellers.size();
        st.totalAmount   = totalAmt;
        st.totalMoney    = totalMoney;
        st.min    = sorted[0];
        st.max    = sorted[sorted.length - 1];
        st.q1     = quantile(sorted, 0.25);
        st.median = quantile(sorted, 0.50);
        st.q3     = quantile(sorted, 0.75);
        return st;
    }

    private List<WeekPt> computeWeeklySeries(EconomyData.Item item, String start, String end, boolean trades) {
        if (item.sales == null) return new ArrayList<>();
        Map<String, List<Double>> grouped = new TreeMap<>();
        for (EconomyData.Sale s : item.sales) {
            if (!trades && "trade".equals(s.s)) continue;
            if (!start.isEmpty() && s.d.compareTo(start) < 0) continue;
            if (!end.isEmpty()   && s.d.compareTo(end)   > 0) continue;
            grouped.computeIfAbsent(weekStart(s.d), k -> new ArrayList<>()).add(s.ppi);
        }
        List<WeekPt> result = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : grouped.entrySet()) {
            double[] sorted = e.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
            WeekPt pt = new WeekPt();
            pt.date   = e.getKey();
            pt.median = quantile(sorted, 0.5);
            pt.n      = sorted.length;
            result.add(pt);
        }
        List<WeekPt> filtered = result.stream().filter(p -> p.n >= 2).collect(Collectors.toList());
        return filtered.size() >= 3 ? filtered : result;
    }

    private void recomputeWorldBuckets() {
        if (data == null || data.items == null) { worldBuckets = new LinkedHashMap<>(); return; }
        worldBuckets = new LinkedHashMap<>();

        String minDate = null;
        if (!"all".equals(worldsDays) && data.generated_at != null) {
            try {
                minDate = subtractDays(data.generated_at, Integer.parseInt(worldsDays) - 1);
            } catch (NumberFormatException ignored) {}
        }

        Map<String, List<WorldItem>> buckets = new HashMap<>();
        final String minD = minDate;
        for (EconomyData.Item it : data.items) {
            if (it.sales == null) continue;
            List<Double> ppis = new ArrayList<>();
            long vol = 0; int tx = 0;
            for (EconomyData.Sale s : it.sales) {
                if (!worldsTrades && "trade".equals(s.s)) continue;
                if (minD != null && s.d.compareTo(minD) < 0) continue;
                ppis.add(s.ppi);
                vol += s.a;
                tx++;
            }
            if (tx == 0) continue;
            double[] sorted = ppis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            WorldItem wi = new WorldItem();
            wi.name   = it.name;
            wi.median = quantile(sorted, 0.5);
            wi.volume = vol;
            wi.tx     = tx;
            String world = it.w != null && !it.w.isEmpty() ? it.w : "General";
            buckets.computeIfAbsent(world, k -> new ArrayList<>()).add(wi);
        }
        for (List<WorldItem> items : buckets.values()) {
            items.sort((a, b) -> Long.compare(b.volume, a.volume));
        }
        for (String world : WORLD_ORDER) {
            if (buckets.containsKey(world)) worldBuckets.put(world, buckets.get(world));
        }

        // Pre-compute total height so scroll works correctly on the first rendered frame
        // Each world card = 24 + items*10 + 4 px, plus 4px gap
        worldsTotalH = 0;
        for (List<WorldItem> items : worldBuckets.values()) {
            worldsTotalH += 24 + items.size() * 10 + 4 + 4;
        }
    }

    private List<EconomyData.DailyEntry> filterDashDays(List<EconomyData.DailyEntry> all, String days) {
        if ("all".equals(days)) return all;
        try {
            int n = Integer.parseInt(days);
            return all.subList(Math.max(0, all.size() - n), all.size());
        } catch (NumberFormatException e) {
            return all.subList(Math.max(0, all.size() - 14), all.size());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Math helpers
    // ──────────────────────────────────────────────────────────────────────────
    private double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        double pos  = (sorted.length - 1) * q;
        int    base = (int) pos;
        double rest = pos - base;
        if (base + 1 < sorted.length) return sorted[base] + rest * (sorted[base + 1] - sorted[base]);
        return sorted[base];
    }

    private String weekStart(String dateStr) {
        try {
            LocalDate d = LocalDate.parse(dateStr);
            d = d.minusDays(d.getDayOfWeek().getValue() - 1);
            return d.toString();
        } catch (DateTimeParseException e) {
            return dateStr;
        }
    }

    private String subtractDays(String dateStr, int days) {
        try {
            return LocalDate.parse(dateStr).minusDays(days).toString();
        } catch (DateTimeParseException e) {
            return dateStr;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Formatting helpers
    // ──────────────────────────────────────────────────────────────────────────
    private String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String fmtMoney(double n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.2fK", n / 1_000.0);
        return String.format("%.2f", n);
    }

    private String fmtPpi(double n) {
        double abs = Math.abs(n);
        if (abs >= 1000) return String.format("%.0f", n);
        if (abs >= 100)  return String.format("%.2f", n);
        if (abs >= 1)    return String.format("%.3f", n);
        return String.format("%.4f", n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Drawing helpers
    // ──────────────────────────────────────────────────────────────────────────
    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y,         x + 1,     y + h,     color);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color);
    }

    private void drawCard(DrawContext ctx, int x, int y, int w, int h, int accent) {
        ctx.fill(x, y, x + w, y + h, C_CARD);
        ctx.fill(x, y, x + 3, y + h, accent);
        drawBorder(ctx, x, y, w, h, C_BORDER);
    }

    /** Bresenham line — draws 2×2 pixel segments */
    private void drawLine(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        for (int iter = 0; iter < 2000; iter++) {
            ctx.fill(x1, y1, x1 + 2, y1 + 2, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 <  dx) { err += dx; y1 += sy; }
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
