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
package skills.intTests.quiz


import org.springframework.beans.factory.annotation.Autowired
import skills.controller.exceptions.ErrorCode
import skills.intTests.utils.DefaultIntSpec
import skills.intTests.utils.QuizDefFactory
import skills.intTests.utils.SkillsClientException
import skills.intTests.utils.SkillsService
import skills.quizLoading.QuizSettings
import skills.storage.repos.*

class QuizApi_MinNumQuestionsToPassSpecs extends DefaultIntSpec {

    def "get quiz info - number of questions required to pass "() {
        def quiz = QuizDefFactory.createQuiz(1, "Fancy Description")
        skillsService.createQuizDef(quiz)
        def questions = QuizDefFactory.createMultipleChoiceQuestions(1, 3, 2)
        skillsService.createQuizQuestionDefs(questions)

        when:
        def quizInfo = skillsService.getQuizInfo(quiz.quizId)

        skillsService.saveQuizSettings(quiz.quizId, [
                [setting: QuizSettings.MinNumQuestionsToPass.setting, value: '2'],
        ])

        def quizInfo_t1 = skillsService.getQuizInfo(quiz.quizId)

        then:
        quizInfo.minNumQuestionsToPass == -1
        quizInfo_t1.minNumQuestionsToPass == 2
    }

    def "pass quiz by getting the exact number of required questions"() {
        def quiz = QuizDefFactory.createQuiz(1, "Fancy Description")
        skillsService.createQuizDef(quiz)
        def questions = QuizDefFactory.createMultipleChoiceQuestions(1, 3, 2)
        skillsService.createQuizQuestionDefs(questions)

        skillsService.saveQuizSettings(quiz.quizId, [
                [setting: QuizSettings.MinNumQuestionsToPass.setting, value: '2'],
        ])

        def quizInfo = skillsService.getQuizInfo(quiz.quizId)
        when:
        def quizAttempt =  skillsService.startQuizAttempt(quiz.quizId).body
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt.id, quizInfo.questions[0].answerOptions[0].id)
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt.id, quizInfo.questions[1].answerOptions[1].id)
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt.id, quizInfo.questions[2].answerOptions[1].id)
        def gradedQuizAttempt = skillsService.completeQuizAttempt(quiz.quizId, quizAttempt.id).body

        def quizAttempt1 =  skillsService.startQuizAttempt(quiz.quizId).body
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt1.id, quizInfo.questions[0].answerOptions[0].id)
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt1.id, quizInfo.questions[1].answerOptions[1].id)
        skillsService.reportQuizAnswer(quiz.quizId, quizAttempt1.id, quizInfo.questions[2].answerOptions[0].id)
        def gradedQuizAttempt1 = skillsService.completeQuizAttempt(quiz.quizId, quizAttempt1.id).body

        then:
        gradedQuizAttempt.passed == false
        gradedQuizAttempt.gradedQuestions.questionId == quizInfo.questions.id
        gradedQuizAttempt.gradedQuestions.isCorrect == [true, false, false]
        gradedQuizAttempt.gradedQuestions[0].selectedAnswerIds == [quizInfo.questions[0].answerOptions[0].id]
        gradedQuizAttempt.gradedQuestions[1].selectedAnswerIds == [quizInfo.questions[1].answerOptions[1].id]
        gradedQuizAttempt.gradedQuestions[2].selectedAnswerIds == [quizInfo.questions[2].answerOptions[1].id]

        gradedQuizAttempt1.passed == true
        gradedQuizAttempt1.gradedQuestions.questionId == quizInfo.questions.id
        gradedQuizAttempt1.gradedQuestions.isCorrect == [true, false, true]
        gradedQuizAttempt1.gradedQuestions[0].selectedAnswerIds == [quizInfo.questions[0].answerOptions[0].id]
        gradedQuizAttempt1.gradedQuestions[1].selectedAnswerIds == [quizInfo.questions[1].answerOptions[1].id]
        gradedQuizAttempt1.gradedQuestions[2].selectedAnswerIds == [quizInfo.questions[2].answerOptions[0].id]
    }

}
