package bg.tusofia.unicloud.portal.staging.dto;

import java.util.List;

/** A faculty and its specialties, for the staging page's faculty and specialty choices. */
public record StagingFacultyResponse(
        String code,
        String name,
        List<SpecialtyItem> specialties
) {

    public record SpecialtyItem(Integer id, String name) {
    }
}
