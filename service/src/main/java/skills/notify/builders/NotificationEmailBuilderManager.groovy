package skills.notify.builders

import org.aspectj.weaver.ast.Not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring5.SpringTemplateEngine
import skills.storage.model.Notification
import groovy.util.logging.Slf4j

import javax.annotation.PostConstruct

@Service
@Slf4j
class NotificationEmailBuilderManager {

    @Autowired
    ApplicationContext appContext

    @Autowired
    SpringTemplateEngine thymeleafTemplateEngine;

    private final Map<String, NotificationEmailBuilder> lookup = [:]

    @PostConstruct
    void init() {
        Collection<NotificationEmailBuilder> loadedBuilders = appContext.getBeansOfType(NotificationEmailBuilder).values()
        loadedBuilders.each { NotificationEmailBuilder builder ->
            assert !lookup.get(builder.getId()), "Found more than 1 builder with the same type [${builder.type}]"
            lookup.put(builder.getId(), builder)
        }
        log.info("Loaded [${lookup.size()}] builders: ${lookup.keySet().toList()}")
    }

    NotificationEmailBuilder.Res build(Notification notification) {
        NotificationEmailBuilder builder = lookup.get(notification.type)
        assert builder
        return builder.build(notification)
    }

    NotificationEmailBuilder.Res buildDigest(List<Notification> notifications) {

        StringBuilder plainText = new StringBuilder()
        plainText.append("Please enjoy your daily SkillTree digest. \n\n")

        Context context = new Context()

        Map<String, List<Notification>> byType = notifications.groupBy { it.type }
        byType.each {
            NotificationEmailBuilder builder = lookup.get(it.key)
            assert builder

            Map<String, String> params = builder.buildDigestParams(it.value)
            params.each {
                context.setVariable(it.key, it.value)
            }

            plainText.append(builder.buildDigestPlainText(notifications))
        }

        plainText.append("\n\n Always yours, \n -SkillTree Bot")

        String htmlBody = thymeleafTemplateEngine.process("daily_digest.html", context)

        return new NotificationEmailBuilder.Res(html: htmlBody, subject: "SkillTree Daily Digest", plainText: plainText.toString())
    }
}
