package com.example.urlshortener.dto;

import java.util.List;

public class PagedResponse<T> {
    private final List<T> items;
    private final int page;
    private final int size;
    private final long totalItems;

    public PagedResponse(List<T> items, int page, int size, long totalItems) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalItems = totalItems;
    }

    public List<T> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalItems() {
        return totalItems;
    }

    public long getTotalPages() {
        return size == 0 ? 0 : (long) Math.ceil((double) totalItems / size);
    }
}
