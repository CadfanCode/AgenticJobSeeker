package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;

import java.util.List;
import java.util.Optional;

public interface AtsTenantRepository extends JpaRepository<AtsTenant, Long> {
    Optional<AtsTenant> findByHost(String host);
    List<AtsTenant> findByVendorAndActiveTrue(AtsVendor vendor);
}
