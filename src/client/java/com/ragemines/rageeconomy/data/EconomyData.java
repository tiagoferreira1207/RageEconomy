package com.ragemines.rageeconomy.data;

import java.util.List;
import java.util.Map;

public class EconomyData {
    public String generated_at;
    public String earliest_date;
    public Dashboard dashboard;
    public List<Item> items;

    public static class Dashboard {
        public int total_ah_sales;
        public int total_orders;
        public int total_trades;
        public Map<String, Integer> world_volumes;
        public List<DailyEntry> daily;
    }

    public static class DailyEntry {
        public String date;
        public int ah;
        public int orders;
        public int trades;
    }

    public static class Item {
        public String name;
        public List<Sale> sales;
        /** Source world for this item */
        public String w;
        /** Total volume sold */
        public long v;
        /** Median price per item (precomputed) */
        public double m;
        /** Total transactions */
        public int tx;
    }

    public static class Sale {
        /** Date YYYY-MM-DD */
        public String d;
        /** Time HH:MM:SS */
        public String t;
        /** Username */
        public String u;
        /** Amount */
        public int a;
        /** Total price */
        public double p;
        /** Price per item */
        public double ppi;
        /** Source: "ah", "order", "trade" */
        public String s;
    }
}
