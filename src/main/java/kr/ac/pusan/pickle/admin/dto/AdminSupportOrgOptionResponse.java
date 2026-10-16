package kr.ac.pusan.pickle.admin.dto;

import java.util.UUID;
import kr.ac.pusan.pickle.orgs.OrgStatus;

/** Safe institution labels for support filters, without settings or credentials. */
public record AdminSupportOrgOptionResponse(UUID id, String name, OrgStatus status) { }
