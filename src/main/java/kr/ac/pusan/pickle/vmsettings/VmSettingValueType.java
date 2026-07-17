package kr.ac.pusan.pickle.vmsettings;

/**
 * Value type of a VM setting (contract schema {@code VmSettingValueType}) —
 * drives the console editor (BOOLEAN → toggle, ENUM → {@code allowedValues}
 * select). Separate catalog from the platform {@code SettingValueType}.
 */
public enum VmSettingValueType {
    BOOLEAN,
    ENUM,
    INTEGER,
    STRING
}
