package com.example.countryinfo.controller;

import com.example.countryinfo.dto.ApiError;
import com.example.countryinfo.dto.CountryResponse;
import com.example.countryinfo.dto.CreateCountryRequest;
import com.example.countryinfo.dto.PageResponse;
import com.example.countryinfo.dto.UpdateCountryRequest;
import com.example.countryinfo.service.CountryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/** HTTP concerns only: binding, validation and status codes. All logic lives in {@link CountryService}. */
@RestController
@RequestMapping(path = "/api/v1/countries", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Countries")
public class CountryController {

    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resolve a country by name via SOAP and store it")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Invalid body", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown country name", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Already stored; Location points to it", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Invalid upstream response", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Upstream unavailable; see Retry-After", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CountryResponse> create(@Valid @RequestBody CreateCountryRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        CountryResponse created = service.create(request);
        URI location = uriBuilder.path("/api/v1/countries/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    @Operation(summary = "List stored countries (paged; sort e.g. name,asc)")
    public PageResponse<CountryResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a stored country by id")
    public CountryResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace the editable fields and languages of a stored country")
    public CountryResponse update(@PathVariable Long id, @Valid @RequestBody UpdateCountryRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a stored country")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
