package skills.controller;

import callStack.profiler.CProf;
import groovy.lang.Closure;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import skills.PublicProps;
import skills.auth.UserInfoService;
import skills.controller.exceptions.ErrorCode;
import skills.controller.exceptions.SkillException;
import skills.controller.exceptions.SkillsValidator;
import skills.controller.request.model.SkillEventRequest;
import skills.services.ProjectErrorService;
import skills.services.events.SkillEventResult;
import skills.services.events.SkillEventsService;
import skills.utils.RetryUtil;

import java.util.Date;
import java.util.Locale;

@Component
public class AddSkillHelper {

    static final DateTimeFormatter DTF = ISODateTimeFormat.dateTimeNoMillis().withLocale(Locale.ENGLISH).withZoneUTC();
    private Logger log = LoggerFactory.getLogger(AddSkillHelper.class);
    @Autowired
    PublicProps publicProps;
    @Autowired
    UserInfoService userInfoService;
    @Autowired
    SkillEventsService skillsManagementFacade;
    @Autowired
    ProjectErrorService projectErrorService;

    public SkillEventResult addSkill(String projectId, String skillId, SkillEventRequest skillEventRequest) {
        String requestedUserId = skillEventRequest != null ? skillEventRequest.getUserId() : null;
        Long requestedTimestamp = skillEventRequest != null ? skillEventRequest.getTimestamp() : null;
        Boolean notifyIfSkillNotApplied = skillEventRequest != null ? skillEventRequest.getNotifyIfSkillNotApplied() : false;
        Boolean isRetry = skillEventRequest != null ? skillEventRequest.getIsRetry() : false;

        Date incomingDate = null;

        if (skillEventRequest != null && requestedTimestamp != null && requestedTimestamp > 0) {
            //let's account for some possible clock drift
            SkillsValidator.isTrue(requestedTimestamp <= (System.currentTimeMillis() + 30000), "Skill Events may not be in the future", projectId, skillId);
            incomingDate = new Date(requestedTimestamp);
        }

        if (skillEventRequest != null && skillEventRequest.getApprovalRequestedMsg() != null) {
            int maxLength = publicProps.getInt(PublicProps.UiProp.maxSelfReportMessageLength);
            int msgLength = skillEventRequest.getApprovalRequestedMsg().length();
            SkillsValidator.isTrue(msgLength <= maxLength, String.format("message has length of %d, maximum allowed length is %d", msgLength, maxLength), projectId, skillId);
        }

        SkillEventResult result;
        String userId = userInfoService.getUserName(requestedUserId, false);
        if (log.isInfoEnabled()) {
            log.info("ReportSkill (ProjectId=[{}], SkillId=[{}], CurrentUser=[{}], RequestUser=[{}], RequestDate=[{}], IsRetry=[{}])",
                    new String[]{projectId, skillId, userInfoService.getCurrentUserId(), requestedUserId, toDateString(requestedTimestamp), isRetry.toString()});
        }

        String prof = "retry-reportSkill";
        CProf.start(prof);
        try {
            final Date dataParam = incomingDate;
            Closure<SkillEventResult> closure = new Closure<SkillEventResult>(null) {
                @Override
                public SkillEventResult call() {
                    SkillEventsService.SkillApprovalParams skillApprovalParams = (skillEventRequest !=null && skillEventRequest.getApprovalRequestedMsg() != null) ?
                            new SkillEventsService.SkillApprovalParams(skillEventRequest.getApprovalRequestedMsg()) : SkillEventsService.getDefaultSkillApprovalParams();
                    return skillsManagementFacade.reportSkill(projectId, skillId, userId, notifyIfSkillNotApplied, dataParam, skillApprovalParams);
                }
            };
            result = (SkillEventResult) RetryUtil.withRetry(3, false, closure);
        } catch(SkillException ske) {
            if (ske.getErrorCode() == ErrorCode.SkillNotFound) {
                projectErrorService.invalidSkillReported(projectId, skillId);
            }
            throw ske;
        }finally {
            CProf.stop(prof);
        }
        return result;
    }

    private String toDateString(Long timestamp) {
        if (timestamp != null) {
            return DTF.print(timestamp);
        }

        return "";
    }
}
