package kr.ac.pusan.pickle.security.institution.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InstitutionPermissionMode", description = "기관 권한 부여 방식")
public enum InstitutionPermissionMode { FULL, SELECTED }
