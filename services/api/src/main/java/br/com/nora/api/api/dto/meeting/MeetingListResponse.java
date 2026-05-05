package br.com.nora.api.api.dto.meeting;

import java.util.List;

public record MeetingListResponse(
        List<MeetingListItem> items, int page, int size, long totalItems, int totalPages) {}
