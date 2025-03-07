package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult
import akka.persistence.query.{NoOffset, Sequence}
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class DeltaClientSpec
    extends TestKit(ActorSystem("DeltaClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with IOValues
    with ConfigFixtures
    with BeforeAndAfterAll
    with TestHelpers
    with QueryParamsUnmarshalling {

  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  var server: Option[Http.ServerBinding] = None

  val token = "secretToken"

  val stats = """{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "eventsCount" : 10,
          "resourcesCount" : 10
        }"""

  implicit val sc: Scheduler = Scheduler.global
  val nQuads                 = contentOf("resource.nq")
  val resourceId             = iri"https://example.com/testresource"

  override def beforeAll(): Unit = {
    super.beforeAll()
    server = Some(
      Http()
        .newServerAt("localhost", 8080)
        .bindFlow(
          RouteResult.routeToFlow(
            extractCredentials {
              case Some(OAuth2BearerToken(`token`)) =>
                concat(
                  (get & path("v1" / "projects" / "org" / "proj" / "statistics")) {
                    complete(StatusCodes.OK, HttpEntity(ContentType(RdfMediaTypes.`application/ld+json`), stats))
                  },
                  (get & path("v1" / "resources" / "org" / "proj" / "events")) {
                    complete(
                      StatusCodes.OK,
                      Source.fromIterator(() => Iterator.from(0)).map { i =>
                        ServerSentEvent(i.toString, "test", i.toString)
                      }
                    )
                  },
                  (head & path("v1" / "resources" / "org" / "proj" / "events")) {
                    complete(StatusCodes.OK)
                  },
                  (pathPrefix(
                    "v1" / "resources" / "org" / "proj" / "_" / "https://example.com/testresource"
                  ) & pathEndOrSingleSlash & parameter("tag".as[TagLabel].?)) {
                    case None                       =>
                      complete(StatusCodes.OK, HttpEntity(ContentType(RdfMediaTypes.`application/n-quads`), nQuads))
                    case Some(TagLabel("knowntag")) =>
                      complete(StatusCodes.OK, HttpEntity(ContentType(RdfMediaTypes.`application/n-quads`), nQuads))
                    case Some(_)                    => complete(StatusCodes.NotFound)
                  }
                )
              case _                                =>
                complete(StatusCodes.Forbidden)
            }
          )
        )
        .futureValue
    )
  }

  override def afterAll(): Unit = {
    server.foreach(_.unbind())
    super.afterAll()
  }

  implicit val httpCfg: HttpClientConfig = httpClientConfig
  private val deltaClient                = DeltaClient(HttpClient(), 1.second)

  private val source = RemoteProjectSource(
    iri"http://example.com/remote-project-source",
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    includeDeprecated = false,
    ProjectRef(Label.unsafe("org"), Label.unsafe("proj")),
    Uri("http://localhost:8080/v1"),
    Some(AccessToken(Secret(token)))
  )

  private val unknownProjectSource = source.copy(project = ProjectRef(Label.unsafe("org"), Label.unsafe("unknown")))

  private val unknownToken = source.copy(token = Some(AccessToken(Secret("invalid"))))

  "Getting project statistics" should {

    "work" in {
      deltaClient.projectCount(source).accepted shouldEqual ProjectCount(10, 10, Instant.EPOCH)
    }

    "fail if project is unknown" in {
      deltaClient.projectCount(unknownProjectSource).rejected.errorCode.value shouldEqual StatusCodes.NotFound
    }

    "fail if token is invalid" in {
      deltaClient.projectCount(unknownToken).rejected.errorCode.value shouldEqual StatusCodes.Forbidden
    }
  }

  "Getting events" should {
    "work" in {
      val stream = deltaClient.events[Int](source, NoOffset)

      val expected = (0 to 4).map { i =>
        Sequence(i.toLong) -> i
      }

      stream.take(5).compile.toList.accepted shouldEqual expected
    }
  }

  "Getting resource as nquads" should {
    "work" in {
      deltaClient.resourceAsNQuads(source, resourceId, None).accepted.value shouldEqual NQuads(nQuads, resourceId)
    }
    "work with tag" in {
      deltaClient
        .resourceAsNQuads(source, resourceId, Some(TagLabel.unsafe("knowntag")))
        .accepted
        .value shouldEqual NQuads(nQuads, resourceId)
    }

    "return None if tag doesn't exist" in {
      deltaClient.resourceAsNQuads(source, resourceId, Some(TagLabel.unsafe("unknowntag"))).accepted shouldEqual None
    }

    "fail if token is invalid" in {}
  }

  "Checking events" should {
    "work" in {
      deltaClient.checkEvents(source).accepted
    }
    "fail if token is invalid" in {
      deltaClient.checkEvents(unknownToken).rejected.errorCode.value shouldEqual StatusCodes.Forbidden
    }
  }

}
