package se.caiowain.jobseeker.tailor.api.dto;

import java.util.List;

public record RequirementDto(Long id, String text, int ordinal, boolean overBroad,
                             List<EvidenceDto> evidence) {
}
