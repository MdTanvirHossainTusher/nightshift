package com.example.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the daily dealer stock report.
 *
 * <p>Seeded defect #3: one query for the dealer list, then one query per dealer
 * for its items — 1 200 statements for a 1 200-row report. Nothing here ever
 * throws, so this defect exists in the logs only as a slow-query WARN and a
 * request-duration WARN. A tool that only greps for ERROR never finds it, which
 * is the point.
 */
public class InventoryReportService {

    private final DealerRepository dealers;
    private final StockItemRepository items;

    public InventoryReportService(DealerRepository dealers, StockItemRepository items) {
        this.dealers = dealers;
        this.items = items;
    }

    public List<DealerStock> buildDailyReport(String region) {
        List<DealerStock> report = new ArrayList<>();
        for (Dealer dealer : dealers.findByRegion(region)) {
            // One round trip per dealer. Should be a single join, or one
            // findByDealerIdIn call outside the loop.
            List<StockItem> stock = items.findByDealerId(dealer.id()); // NS_FRAME_NPLUSONE
            report.add(new DealerStock(dealer, stock));
        }
        return report;
    }

    public interface DealerRepository {
        List<Dealer> findByRegion(String region);
    }

    public interface StockItemRepository {
        List<StockItem> findByDealerId(long dealerId);
    }

    public record Dealer(long id, String name, String region) {
    }

    public record StockItem(long id, long dealerId, String sku, int quantity) {
    }

    public record DealerStock(Dealer dealer, List<StockItem> items) {
    }
}
