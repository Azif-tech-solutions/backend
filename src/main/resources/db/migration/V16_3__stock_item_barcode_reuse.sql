-- Recycle Bin barcode reuse (docs/10 §4.9). A deleted item keeps its barcode in the bin, but the
-- barcode becomes reusable by a live item while it sits there: the uniqueness index now excludes
-- recycled statuses (DELETED keeps its barcode; PURGED already releases it to null). Restore
-- re-checks and raises DUPLICATE_BARCODE if a live item took the barcode meanwhile.
drop index if exists stock_items_barcode_idx;
create unique index stock_items_barcode_idx on stock_items (tenant_id, barcode)
    where barcode is not null and status not in ('DELETED', 'PURGED');
