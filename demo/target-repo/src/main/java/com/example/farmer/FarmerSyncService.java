package com.example.farmer;

import com.example.common.NameFormatter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Pushes pending farmer records upstream.
 *
 * <p>Seeded defect #1: the connection is borrowed at the top of the loop body and
 * closed only on the success path. Every row that throws leaks one connection from
 * a pool capped at 10, so the pool degrades monotonically under load.
 *
 * <p>Seeded defect #2: {@link NameFormatter#initials} is called with a middle name
 * that the schema allows to be null.
 */
public class FarmerSyncService {

    private final DataSource dataSource;
    private final UpstreamClient upstream;

    public FarmerSyncService(DataSource dataSource, UpstreamClient upstream) {
        this.dataSource = dataSource;
        this.upstream = upstream;
    }

    public int pushPending(List<FarmerRecord> pending) throws SQLException {
        int pushed = 0;
        for (FarmerRecord record : pending) {
            // The borrow is NOT in a try-with-resources. `upstream.push` below can
            // throw, and when it does this connection is never returned.
            try (Connection connection = dataSource.getConnection()) { // NS_FRAME_POOL

            PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE farmer SET synced_at = now() WHERE id = ?");
            stmt.setLong(1, record.id());

            upstream.push(record);
            stmt.executeUpdate();

            connection.close();
            pushed++;
            }
        }
        return pushed;
    }

    /** Called while rendering the card label for a synced farmer. */
    public String cardLabel(FarmerRecord record) {
        return NameFormatter.initials(record.firstName(), record.middleName(), record.lastName()); // NS_FRAME_NPE
    }

    public interface UpstreamClient {
        void push(FarmerRecord record);
    }

    public record FarmerRecord(long id, String firstName, String middleName, String lastName) {
    }
}
