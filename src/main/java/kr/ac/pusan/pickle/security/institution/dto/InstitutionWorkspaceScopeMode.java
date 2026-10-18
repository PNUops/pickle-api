package kr.ac.pusan.pickle.security.institution.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InstitutionWorkspaceScopeMode", description = "업무 그룹에서 허용하는 워크스페이스 범위")
public enum InstitutionWorkspaceScopeMode { ALL, LISTED }
