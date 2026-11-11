package com.example.quickdrop.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface ShareBundleRepository
        extends JpaRepository<ShareBundle, String> {
    @Query("select distinct b from ShareBundle b join b.fileTokens t where t in :tokens")
    List<ShareBundle> findContainingTokens(@Param("tokens") Collection<String> tokens);
}
