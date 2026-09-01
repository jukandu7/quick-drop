package com.example.quickdrop.share;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "share_bundles")
public class ShareBundle {

    @Id
    private String token;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "share_bundle_files",
            joinColumns = @JoinColumn(name = "bundle_token")
    )
    @OrderColumn(name = "file_order")
    @Column(name = "file_token", nullable = false)
    private List<String> fileTokens = new ArrayList<>();

    protected ShareBundle() {
    }

    public ShareBundle(String token, List<String> fileTokens) {
        this.token = token;
        this.fileTokens = new ArrayList<>(fileTokens);
    }

    public List<String> getFileTokens() {
        return fileTokens;
    }

    public void replaceFileTokens(List<String> fileTokens) {
        this.fileTokens.clear();
        this.fileTokens.addAll(fileTokens);
    }
}
