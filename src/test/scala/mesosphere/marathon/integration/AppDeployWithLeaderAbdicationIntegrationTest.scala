package mesosphere.marathon
package integration

import java.io.File
import java.util.UUID

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.integration.facades.MarathonFacade
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{ AppHealthCheck, AppUpdate, PortDefinition }
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Try

@IntegrationTest
class AppDeployWithLeaderAbdicationIntegrationTest extends AkkaIntegrationFunTest with MarathonClusterTest {
  private[this] val log = LoggerFactory.getLogger(getClass)

  after(cleanUp())

  // ___________________    . , ; .
  //(___________________|~~~~~X.;' .
  //                        ' `" ' `
  //                        TNT
  test("deployment is restarted properly on master abdication") {
    Given("a new app with 1 instance and no health checks")
    val appId = testBasePath / "app"

    val appv1 = appProxy(appId, "v1", instances = 1, healthCheck = None)
    val create = marathon.createAppV2(appv1)
    create.code should be (201)
    waitForDeployment(create)

    val started = marathon.tasks(appId)
    val startedTaskIds = started.value.map(_.id)
    log.info(s"Started app: ${marathon.app(appId).entityPrettyJsonString}")

    When("updated with health check and minimumHealthCapacity=1")

    // ServiceMock will block the deployment (returning HTTP 503) until called continue()
    val plan = "phase(block1)"
    marathon.updateApp(appId, AppUpdate(
      cmd = Some(s"""$serviceMockScript '$plan'"""),
      portDefinitions = Some(Seq(PortDefinition(0, name = Some("http")))),
      healthChecks = Some(Seq(ramlHealthCheck)),
      upgradeStrategy = Some(raml.UpgradeStrategy(minimumHealthCapacity = 1.0, maximumOverCapacity = 1.0))))

    And("new and updated task is started successfully")
    waitForTasks(appId, 2, maxWait = 90.seconds) //make sure, the new task has really started

    val updatedTask = updated.diff(started.value).head
    val updatedTaskIds: List[String] = updated.map(_.id).diff(startedTaskIds)

    And("service mock is responding")
    val serviceFacade = new ServiceMockFacade(updatedTask)
    WaitTestSupport.waitUntil("ServiceMock is up", 30.seconds){ Try(serviceFacade.plan()).isSuccess }

    log.info(s"Updated app: ${marathon.app(appId).entityPrettyJsonString}")

    When("marathon leader is abdicated")
    val leader = marathon.leader().value
    val secondary = nonLeader(leader)
    val leaderFacade = new MarathonFacade(s"http://${leader.leader}", PathId.empty)
    leaderFacade.abdicate().code should be (200)

    And("a new leader is elected")
    WaitTestSupport.waitUntil("the leader changes", 30.seconds) { secondary.leader().value != leader }

    And("the updated task becomes healthy")
    // This would move the service mock from "InProgress" [HTTP 503] to "Complete" [HTTP 200]
    serviceFacade.continue()
    waitForEvent("health_status_changed_event")

    Then("the app should have only 1 task launched")
    waitForTasks(appId, 1)(secondary) should have size 1

    And("app was deployed successfully")
    waitForEventMatching("app should be restarted and deployment should be finished") { matchDeploymentSuccess(1, appId.toString) }

    val after = secondary.tasks(appId)
    val afterTaskIds = after.value.map(_.id)
    log.info(s"App after restart: ${secondary.app(appId).entityPrettyJsonString}")

    And("taskId after restart should be equal to the updated taskId (not started one)")
    afterTaskIds should equal (updatedTaskIds)
  }

  private def matchDeploymentSuccess(instanceCount: Int, appId: String): CallbackEvent => Boolean = { event =>
    val infoString = event.info.toString()
    event.eventType == "deployment_success" && infoString.contains(s"instances -> $instanceCount") && matchRestartApplication(infoString, appId)
  }

  private def matchRestartApplication(infoString: String, appId: String): Boolean = {
    infoString.contains(s"List(Map(actions -> List(Map(action -> RestartApplication, app -> $appId)))))")
  }

  private lazy val ramlHealthCheck: AppHealthCheck = AppHealthCheck(
    path = Some("/v1/plan"),
    portIndex = Some(0),
    intervalSeconds = 2,
    timeoutSeconds = 1
  )

  /**
    * Create a shell script that can start a service mock
    */
  private lazy val serviceMockScript: String = {
    val uuid = UUID.randomUUID.toString
    appProxyIds(_ += uuid)
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -DappProxyId=$uuid -DtestSuite=$suiteName -Xmx64m -classpath $classPath $main"""
    val file = File.createTempFile("serviceProxy", ".sh")
    file.deleteOnExit()

    FileUtils.write(
      file,
      s"""#!/bin/sh
          |set -x
          |exec $run $$*""".stripMargin)
    file.setExecutable(true)
    file.getAbsolutePath
  }
}
