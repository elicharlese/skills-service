/**
 * Copyright 2020 SkillTree
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package skills.services

import callStack.profiler.Profile
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import skills.auth.UserInfo
import skills.auth.UserInfoService
import skills.auth.UserSkillsGrantedAuthority
import skills.controller.exceptions.ErrorCode
import skills.controller.exceptions.SkillException
import skills.controller.exceptions.SkillsValidator
import skills.controller.request.model.SkillApproverConfRequest
import skills.controller.result.model.LabelCountItem
import skills.controller.result.model.SkillApprovalResult
import skills.controller.result.model.TableResult
import skills.controller.result.model.UserRoleRes
import skills.notify.EmailNotifier
import skills.notify.Notifier
import skills.services.admin.SkillCatalogService
import skills.services.events.SkillEventResult
import skills.services.events.SkillEventsService
import skills.services.settings.SettingsService
import skills.storage.accessors.SkillDefAccessor
import skills.storage.model.*
import skills.storage.model.auth.RoleName
import skills.storage.repos.ProjDefRepo
import skills.storage.repos.SkillApprovalConfRepo
import skills.storage.repos.SkillApprovalRepo
import skills.storage.repos.SkillDefRepo
import skills.storage.repos.UserAttrsRepo
import skills.utils.InputSanitizer

import java.util.stream.Stream

@Service
@Slf4j
class SkillApprovalService {

    @Autowired
    SkillApprovalRepo skillApprovalRepo

    @Autowired
    SkillEventsService skillEventsService

    @Autowired
    SkillDefRepo skillDefRepo

    @Autowired
    SkillDefAccessor skillDefAccessor

    @Autowired
    ProjDefRepo projDefRepo

    @Autowired
    UserAttrsRepo userAttrsRepo

    @Autowired
    SettingsService settingsService

    @Autowired
    UserInfoService userInfoService

    @Autowired
    EmailNotifier notifier

    @Autowired
    FeatureService featureService

    @Autowired
    SkillCatalogService skillCatalogService

    @Autowired
    SkillApprovalConfRepo skillApprovalConfRepo

    @Autowired
    AccessSettingsStorageService accessSettingsStorageService

    TableResult getApprovals(String projectId, PageRequest pageRequest) {
        String currentApproverId = userInfoService.currentUser.username
        Boolean confExistForApprover = skillApprovalConfRepo.confExistForApprover(projectId, currentApproverId)

        return buildApprovalsResult(projectId, pageRequest, {
            if (confExistForApprover) {
                skillApprovalRepo.findToApproveWithApproverConf(projectId, currentApproverId, pageRequest)
            } else {
                skillApprovalRepo.findToApproveByProjectIdAndNotRejectedOrApproved(projectId, pageRequest)
            }
        }, {
            if (confExistForApprover) {
                skillApprovalRepo.countToApproveWithApproverConf(projectId, currentApproverId)
            } else {
                skillApprovalRepo.countByProjectIdAndApproverUserIdIsNull(projectId)
            }
        })
    }

    TableResult getApprovalsHistory(String projectId, String skillNameFilter, String userIdFilter, String approverUserIdFilter, PageRequest pageRequest) {
        UserInfo userInfo = userInfoService.currentUser
        boolean isApprover = userInfo.authorities?.find() {
            it instanceof UserSkillsGrantedAuthority && RoleName.ROLE_PROJECT_APPROVER == it.role?.roleName
        }
        String optionalApproverUserIdOrKeywordAll = isApprover ? userInfo.username.toLowerCase() : "All"

        return buildApprovalsResult(projectId, pageRequest, {
            skillApprovalRepo.findApprovalsHistory(projectId, skillNameFilter, userIdFilter, approverUserIdFilter, optionalApproverUserIdOrKeywordAll, pageRequest)
        }, {
            skillApprovalRepo.countApprovalsHistory(projectId, skillNameFilter, userIdFilter, approverUserIdFilter, optionalApproverUserIdOrKeywordAll)
        })
    }

    private TableResult buildApprovalsResult(String projectId, PageRequest pageRequest, Closure<List<SkillApprovalRepo.SimpleSkillApproval>> getData, Closure<Integer> getCount) {
        List<SkillApprovalRepo.SimpleSkillApproval> approvalsFromDB = getData.call()
        List<SkillApprovalResult> approvals = approvalsFromDB.collect { SkillApprovalRepo.SimpleSkillApproval simpleSkillApproval ->
            new SkillApprovalResult(
                    id: simpleSkillApproval.getApprovalId(),
                    userId: simpleSkillApproval.getUserId(),
                    userIdForDisplay: simpleSkillApproval.getUserIdForDisplay(),
                    projectId: projectId,
                    subjectId: simpleSkillApproval.getSubjectId(),
                    skillId: simpleSkillApproval.getSkillId(),
                    skillName: InputSanitizer.unsanitizeName(simpleSkillApproval.getSkillName()),
                    requestedOn: simpleSkillApproval.getRequestedOn().time,
                    approverActionTakenOn: simpleSkillApproval.getApproverActionTakenOn()?.time,
                    rejectedOn: simpleSkillApproval.getRejectedOn()?.time,
                    requestMsg: simpleSkillApproval.getRequestMsg(),
                    rejectionMsg: simpleSkillApproval.getRejectionMsg(),
                    points: simpleSkillApproval.getPoints(),
                    approverUserId: simpleSkillApproval.getApproverUserId(),
                    approverUserIdForDisplay: simpleSkillApproval.getApproverUserIdForDisplay(),
            )
        }

        Integer count = approvals.size()
        Integer totalCount = approvals.size()
        if (totalCount >= pageRequest.pageSize || pageRequest.pageSize > 1) {
            totalCount = getCount.call()
            // always the same since filter is never provided
            count = totalCount
        }

        TableResult tableResult = new TableResult(
                data: approvals,
                count: count,
                totalCount: totalCount
        )

        return tableResult
    }

    void approve(String projectId, List<Integer> approvalRequestIds) {
        List<SkillApproval> toApprove = skillApprovalRepo.findAllById(approvalRequestIds)
        toApprove.each {
            validateProjId(it, projectId)

            Optional<SkillDef> optional = skillDefRepo.findById(it.skillRefId)
            if (optional.isPresent()) {
                SkillDef skillDef = optional.get()
                // enter SkillEventResult for all copies
                SkillEventResult res = skillEventsService.reportSkill(projectId, skillDef.skillId, it.userId, false, it.requestedOn,
                        new SkillEventsService.SkillApprovalParams(disableChecks: true))

                if (log.isDebugEnabled()) {
                    log.debug("Approval for ${it} yielded:\n${res}")
                }

                it.approverActionTakenOn = new Date()
                it.approverUserId = userInfoService.currentUser.username
                skillApprovalRepo.save(it)

                // send email
                sentNotifications(it, skillDef, true)
            }
        }
    }

    void reject(String projectId, List<Integer> approvalRequestIds, String rejectionMsg) {
        List<SkillApproval> toReject = skillApprovalRepo.findAllById(approvalRequestIds)
        toReject.each {
            validateProjId(it, projectId)

            Date now = new Date()
            it.rejectionMsg = rejectionMsg
            it.rejectedOn = now
            it.approverActionTakenOn = now
            it.approverUserId = userInfoService.currentUser.username

            skillApprovalRepo.save(it)

            // send email
            Optional<SkillDef> optional = skillDefRepo.findById(it.skillRefId)
            SkillDef skillDef = optional.get()
            sentNotifications(it, skillDef, false, rejectionMsg)
        }
    }

    List<LabelCountItem> getSelfReportStats(String projectId) {
        List<SkillApprovalRepo.SkillReportingTypeAndCount> drRes = skillApprovalRepo.skillCountsGroupedByApprovalType(projectId)
        return drRes.collect {
            new LabelCountItem(
                    value: it.getType() ?: 'Disabled',
                    count: it.getCount()
            )
        }
    }

    List<LabelCountItem> getSkillApprovalsStats(String projectId, String skillId) {
        SkillRequestApprovalStats stats = skillApprovalRepo.countSkillRequestApprovals(projectId, skillId)
        return [
                new LabelCountItem(value: 'SkillApprovalsRequests', count: stats != null ? stats.getPending() : 0),
                new LabelCountItem(value: 'SkillApprovalsRejected', count: stats != null ? stats.getRejected() : 0),
                new LabelCountItem(value: 'SkillApprovalsApproved', count: stats != null ? stats.getApproved() : 0)
        ]
    }

    @Profile
    void modifyApprovalsWhenSelfReportingTypeChanged(SkillDefWithExtra existing, SkillDef.SelfReportingType incomingType) {
        if (existing.selfReportingType == incomingType) {
            return;
        }

        if (existing.selfReportingType == SkillDef.SelfReportingType.Approval && !incomingType) {
            skillApprovalRepo.deleteByProjectIdAndSkillRefId(existing.projectId, existing.id)
        } else if (existing.selfReportingType == SkillDef.SelfReportingType.Approval && incomingType == SkillDef.SelfReportingType.HonorSystem) {
            skillApprovalRepo.findAllBySkillRefIdAndRejectedOnIsNull(existing.id).withCloseable { Stream<SkillApproval> existingApprovals ->
                existingApprovals.forEach({ SkillApproval skillApproval ->
                    SkillEventResult res = skillEventsService.reportSkill(existing.projectId, existing.skillId, skillApproval.userId, false,
                            skillApproval.requestedOn, new SkillEventsService.SkillApprovalParams(disableChecks: true))
                    if (log.isDebugEnabled()) {
                        log.debug("Approval for ${skillApproval} yielded:\n${res}")
                    }
                })
            }
            skillApprovalRepo.deleteByProjectIdAndSkillRefId(existing.projectId, existing.id)
        }
    }

    private void validateProjId(SkillApproval skillApproval, String projectId) {
        if (skillApproval.projectId != projectId) {
            throw new SkillException("Provided approval id [${skillApproval.id}] does not belong to [${projectId}]", projectId, null, ErrorCode.BadParam)
        }
    }

    private void sentNotifications(SkillApproval skillApproval, SkillDef skillDefinition, boolean approved, String rejectionMsg=null) {
        String publicUrl = featureService.getPublicUrl()
        if(!publicUrl) {
            return
        }

        UserAttrs userAttrs = userAttrsRepo.findByUserId(skillApproval.userId)
        if (!userAttrs.email) {
            return
        }

        ProjectSummaryResult projDef = projDefRepo.getProjectName(skillDefinition.projectId)
        Notifier.NotificationRequest request = new Notifier.NotificationRequest(
                userIds: [skillApproval.userId],
                type: Notification.Type.SkillApprovalResponse.toString(),
                keyValParams: [
                        approver     : userInfoService.currentUser.usernameForDisplay,
                        approved     : approved,
                        skillName    : skillDefinition.name,
                        skillId      : skillDefinition.skillId,
                        projectName  : projDef.getProjectName(),
                        projectId    : skillDefinition.projectId,
                        rejectionMsg : rejectionMsg,
                        publicUrl    : publicUrl,
                ],
        )
        notifier.sendNotification(request)
    }

    @Transactional
    void configureApprover(String projectId, String approverId, SkillApproverConfRequest skillApproverConfRequest) {
        validateApproverAccess(projectId, approverId)

        if (skillApproverConfRequest.userId) {
            String userId = skillApproverConfRequest.userId.toLowerCase()
            SkillApprovalConf conf = skillApprovalConfRepo.findByProjectIdAndApproverUserIdAndUserId(projectId, approverId, userId)
            if(!conf) {
                conf = new SkillApprovalConf(projectId: projectId, approverUserId: approverId, userId: userId)
            }
            // update no matter what so the latest date is saved
            skillApprovalConfRepo.save(conf)
        }

        if (skillApproverConfRequest.userTagKey) {
            SkillsValidator.isNotBlank(skillApproverConfRequest.userTagValue, "userTagValue", projectId)

            String userTagKey = skillApproverConfRequest.userTagKey.toLowerCase()
            String userTagValue = skillApproverConfRequest.userTagValue.toLowerCase()
            SkillApprovalConf conf = skillApprovalConfRepo.findByProjectIdAndApproverUserIdAndUserTagKeyAndUserTagValue(projectId, approverId, userTagKey, userTagValue)
            if(!conf) {
                conf = new SkillApprovalConf(projectId: projectId, approverUserId: approverId, userTagKey: userTagKey, userTagValue: userTagValue)
            }
            // update no matter what so the latest date is saved
            skillApprovalConfRepo.save(conf)
        }

        if (skillApproverConfRequest.skillId) {
            SkillDef skillDef = skillDefAccessor.getSkillDef(projectId, skillApproverConfRequest.skillId, [SkillDef.ContainerType.Skill])
            SkillApprovalConf conf = skillApprovalConfRepo.findByProjectIdAndApproverUserIdAndSkillRefId(projectId, approverId, skillDef.id)
            if(!conf) {
                conf = new SkillApprovalConf(projectId: projectId, approverUserId: approverId, skillRefId: skillDef.id)
            }
            // update no matter what so the latest date is saved
            skillApprovalConfRepo.save(conf)
        }
    }

    @Profile
    private void validateApproverAccess(String projectId, String approverId) {
        List<UserRoleRes> requestedApproverRoles = accessSettingsStorageService.getUserRolesForProjectIdAndUserId(projectId, approverId)
        List<RoleName> validRoleNames = [RoleName.ROLE_PROJECT_APPROVER, RoleName.ROLE_PROJECT_ADMIN, RoleName.ROLE_SUPER_DUPER_USER]
        boolean hasValidRole = requestedApproverRoles?.find({ validRoleNames.contains(it.roleName) })
        if (!hasValidRole) {
            throw new SkillException("Approver [${approverId}] does not have permission to approve for the project [${projectId}]", projectId, null, ErrorCode.AccessDenied)
        }
    }
}
