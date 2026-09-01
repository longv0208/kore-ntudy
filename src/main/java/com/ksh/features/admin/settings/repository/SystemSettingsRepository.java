package com.ksh.features.admin.settings.repository;

import com.ksh.entities.SystemSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repository for the {@code system_settings} table.
 *
 * <p>Provides queries by group and key, plus the {@link #loadGroupAsMap}
 * helper that flattens all rows in a group into a {@code Map<key, value>}
 * for convenient consumption by service-layer callers.
 */
@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSetting, Long> {

    /**
     * Returns all settings that belong to the specified group.
     *
     * @param settingGroup the group name (e.g. {@code "SMTP"}, {@code "GENERAL"})
     * @return a list of {@link SystemSetting} entities in that group; never {@code null}
     */
    List<SystemSetting> findBySettingGroup(String settingGroup);

    /** Current read for lifecycle metadata after taking the current-semester mutex. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SystemSetting s WHERE s.settingGroup = :settingGroup")
    List<SystemSetting> findBySettingGroupForUpdate(@Param("settingGroup") String settingGroup);

    /**
     * Looks up a single setting by its unique key.
     *
     * @param settingKey the setting key (e.g. {@code "smtp.host"})
     * @return an {@link Optional} containing the matching setting, or empty if not found
     */
    Optional<SystemSetting> findBySettingKey(String settingKey);

    /**
     * Locks one stable setting row until the surrounding transaction completes.
     *
     * <p>This is used when a mutation needs a database-backed mutex that remains
     * available even when the table being mutated is empty. Callers must acquire
     * this anchor before reading or locking their mutable rows so every code path
     * follows the same lock order.
     *
     * @param settingKey key of the stable row to lock
     * @return the locked row, or empty when the required seed row is missing
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SystemSetting s WHERE s.settingKey = :settingKey")
    Optional<SystemSetting> findBySettingKeyForUpdate(@Param("settingKey") String settingKey);

    /**
     * Loads all rows in the given group and returns them as a flat {@code Map<key, value>}.
     *
     * <p>This is a default interface method — no separate implementation class is needed.
     * {@code null} setting values are normalised to an empty string so callers can safely
     * call {@link String} methods on every map value without null checks.
     *
     * @param settingGroup the group name to load (e.g. {@code "SMTP"})
     * @return a {@link Map} of setting keys to their values; never {@code null}
     */
    default Map<String, String> loadGroupAsMap(String settingGroup) {
        return findBySettingGroup(settingGroup).stream()
                .collect(Collectors.toMap(
                        SystemSetting::getSettingKey,
                        s -> s.getSettingValue() == null ? "" : s.getSettingValue()
                ));
    }
}
