package com.bookmarkdrawer.tagging;

import java.util.List;

public record MetadataExtractionResult(int attempted, int succeeded, int failed, List<MetadataProposal> proposals) {
}
