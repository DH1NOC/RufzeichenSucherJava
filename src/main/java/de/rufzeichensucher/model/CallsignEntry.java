package de.rufzeichensucher.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CallsignEntry implements Comparable<CallsignEntry> {

    private static final Pattern LOCATION_PATTERN = Pattern.compile("(\\d{5})\\s+(.+)");

    @JsonProperty("callsign")
    private String callsign;

    @JsonProperty("licenseClass")
    private String licenseClass;

    @JsonProperty("name")
    private String name;

    @Nullable
    @JsonProperty("address")
    private String address;

    @Nullable
    @JsonProperty("secondaryLocation")
    private String secondaryLocation;

    public CallsignEntry() {}

    public CallsignEntry(String callsign, String licenseClass, String name,
                         @Nullable String address, @Nullable String secondaryLocation) {
        this.callsign = callsign.toUpperCase(Locale.ROOT);
        this.licenseClass = licenseClass;
        this.name = name;
        this.address = address;
        this.secondaryLocation = secondaryLocation;
    }

    public String getCallsign() { return callsign; }
    public void setCallsign(String callsign) { this.callsign = callsign; }

    public String getLicenseClass() { return licenseClass; }
    public void setLicenseClass(String licenseClass) { this.licenseClass = licenseClass; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Nullable
    public String getAddress() { return address; }
    public void setAddress(@Nullable String address) { this.address = address; }

    @Nullable
    public String getSecondaryLocation() { return secondaryLocation; }
    public void setSecondaryLocation(@Nullable String secondaryLocation) {
        this.secondaryLocation = secondaryLocation;
    }

    /** 5-digit postal code derived from address – not stored, not serialized. */
    @JsonIgnore
    @Nullable
    public String getZip() {
        if (address == null) return null;
        var m = LOCATION_PATTERN.matcher(address);
        return m.find() ? m.group(1) : null;
    }

    /** City name derived from address – not stored, not serialized. */
    @JsonIgnore
    @Nullable
    public String getCity() {
        if (address == null) return null;
        var m = LOCATION_PATTERN.matcher(address);
        return m.find() ? m.group(2).trim() : null;
    }

    /** "12345 Musterstadt" – ZIP + city combined for display. */
    @JsonIgnore
    @Nullable
    public String getCityWithZip() {
        var zip  = getZip();
        var city = getCity();
        if (zip == null && city == null) return null;
        if (zip == null) return city;
        if (city == null) return zip;
        return zip + " " + city;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallsignEntry e)) return false;
        return Objects.equals(callsign, e.callsign);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callsign);
    }

    @Override
    public int compareTo(CallsignEntry other) {
        return this.callsign.compareTo(other.callsign);
    }

    @Override
    public String toString() {
        return callsign;
    }
}
