package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.{EventExchangeValue, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.ResourceDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ProjectSetup, ResourcesDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.JsonObject
import io.circe.literal._
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ResourceEventExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)
  implicit private val api: JsonLdApi   = JsonLdJavaApi.strict

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty
    )

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = (_, _) => UIO.none[ResourceF[Schema]]

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(
      res,
      (r, p, _) => resources.fetch(r, p).bimap(_ => ResourceResolutionReport(), _.value)
    )

  private val resolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(project.ref, fetchSchema)

  private lazy val resources: Resources =
    ResourcesDummy(
      orgs,
      projs,
      resolution,
      (_, _) => UIO.unit,
      resolverContextResolution
    ).accepted

  "A ResourceEventExchange" should {
    val id              = iri"http://localhost/${genString()}"
    val source          =
      json"""{
              "@context": {
                "@vocab": ${nxv.base}
              },
              "@type": "Custom",
              "name": "Alex",
              "number": 24,
              "bool": false
            }"""
    val tag             = TagLabel.unsafe("tag")
    val resRev1         = resources.create(id, project.ref, schemas.resources, source).accepted
    val resRev2         = resources.tag(id, project.ref, None, tag, 1L, 1L).accepted
    val deprecatedEvent = ResourceDeprecated(id, project.ref, Set(nxv + "Custom"), 1, Instant.EPOCH, subject)

    val exchange = new ResourceEventExchange(resources)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value.asInstanceOf[EventExchangeValue[_, _]]
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev2
      result.metadata.value shouldEqual ()
    }

    "return the resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value.asInstanceOf[EventExchangeValue[_, _]]
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev1
      result.metadata.value shouldEqual ()
    }

    "return the TagNotFound if the tag has been deleted" in {
      resources.deleteTag(id, project.ref, None, tag, 2L).accepted
      exchange.toResource(deprecatedEvent, Some(tag)).accepted.value shouldBe a[TagNotFound]
    }

    "return the metric" in {
      val metric = exchange.toMetric(deprecatedEvent).accepted.value

      metric shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Deprecated,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxv + "Custom"),
        JsonObject.empty
      )
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : ${contexts.metadata},
          "@type" : "ResourceDeprecated",
          "_resourceId" : $id,
          "_project" : "http://localhost/v1/projects/myorg/myproject",
          "_types" : ["https://bluebrain.github.io/nexus/vocabulary/Custom"],
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
