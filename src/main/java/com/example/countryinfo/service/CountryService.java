package com.example.countryinfo.service;

import com.example.countryinfo.client.CountryDetails;
import com.example.countryinfo.client.CountryInfoClient;
import com.example.countryinfo.dto.CountryResponse;
import com.example.countryinfo.dto.CreateCountryRequest;
import com.example.countryinfo.dto.PageResponse;
import com.example.countryinfo.dto.UpdateCountryRequest;
import com.example.countryinfo.exception.DuplicateCountryException;
import com.example.countryinfo.exception.InvalidRequestException;
import com.example.countryinfo.exception.ResourceNotFoundException;
import com.example.countryinfo.mapper.CountryMapper;
import com.example.countryinfo.model.CountryInfo;
import com.example.countryinfo.repository.CountryInfoRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Orchestrates the create flow and the CRUD operations.
 *
 * <p>The create flow is intentionally <b>not</b> wrapped in one transaction: holding a DB
 * connection open across two remote SOAP calls (up to tens of seconds when the upstream is
 * slow) would let a flaky third party exhaust the connection pool. Only the final insert is
 * transactional, and the unique constraint on iso_code is the real guard against duplicates.
 */
@Service
public class CountryService {

    private static final Logger log = LoggerFactory.getLogger(CountryService.class);

    static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "name", "isoCode", "capitalCity", "continentCode", "currencyIsoCode", "createdAt", "updatedAt");

    private final CountryInfoRepository repository;
    private final CountryInfoClient client;
    private final CountryMapper mapper;
    private final Counter countriesCreated;

    public CountryService(CountryInfoRepository repository, CountryInfoClient client,
                          CountryMapper mapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.client = client;
        this.mapper = mapper;
        this.countriesCreated = Counter.builder("countries.created")
                .description("Countries resolved from the SOAP service and persisted")
                .register(meterRegistry);
    }

    public CountryResponse create(CreateCountryRequest request) {
        String name = CountryNameNormalizer.normalize(request.name());

        // 1. Already stored under this name? Answer from the DB without touching the upstream,
        //    which also keeps known countries working while the SOAP service is down.
        repository.findFirstByNameIgnoreCase(name).ifPresent(existing -> {
            throw new DuplicateCountryException(existing.getId(), existing.getIsoCode());
        });

        // 2. Resolve the ISO code (cached), then check again by the authoritative key, since
        //    the stored upstream name can differ from what the user typed.
        String isoCode = client.resolveIsoCode(name);
        repository.findByIsoCode(isoCode).ifPresent(existing -> {
            throw new DuplicateCountryException(existing.getId(), existing.getIsoCode());
        });

        // 3. Fetch full details and persist.
        CountryDetails details = client.fetchCountryInfo(isoCode);
        CountryInfo saved;
        try {
            saved = repository.saveAndFlush(mapper.toEntity(details));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests for the same country both passed step 2; the unique
            // constraint lets exactly one insert win and the other becomes a clean 409.
            CountryInfo existing = repository.findByIsoCode(isoCode).orElseThrow(() -> e);
            throw new DuplicateCountryException(existing.getId(), existing.getIsoCode());
        }

        countriesCreated.increment();
        log.info("country.created id={} isoCode={} name={} languages={}",
                saved.getId(), saved.getIsoCode(), saved.getName(), saved.getLanguages().size());
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<CountryResponse> list(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new InvalidRequestException("Cannot sort by '" + order.getProperty()
                        + "'. Allowed: " + String.join(", ", SORTABLE_FIELDS.stream().sorted().toList()));
            }
        }
        return PageResponse.from(repository.findAll(pageable).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public CountryResponse get(Long id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional
    public CountryResponse update(Long id, UpdateCountryRequest request) {
        CountryInfo entity = findOrThrow(id);
        mapper.applyUpdate(entity, request);
        CountryInfo saved = repository.saveAndFlush(entity);
        log.info("country.updated id={} isoCode={}", saved.getId(), saved.getIsoCode());
        return mapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        CountryInfo entity = findOrThrow(id);
        repository.delete(entity);
        log.info("country.deleted id={} isoCode={}", id, entity.getIsoCode());
    }

    private CountryInfo findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }
}
