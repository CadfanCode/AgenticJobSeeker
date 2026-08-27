package se.caiowain.jobseeker.api.dto;

import java.util.Map;

public record StatsDto(long totalJobs, Map<String, Long> jobsBySource,
                       Map<String, Long> jobsByVendor, long activeTenants,
                       IngestRunDto lastRun) {
}
