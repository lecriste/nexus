package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection

/**
  * Typeclass definition for ''A''s from which the HttpHeaders and StatusCode can be ontained.
  *
  * @tparam A generic type parameter
  */
trait HttpResponseFields[A] {

  /**
    * Computes a [[StatusCode]] from the argument value.
    *
    * @param value the input value
    */
  def statusFrom(value: A): StatusCode

  /**
    * Computes a sequence of [[HttpHeader]] from the argument value.
    *
    * @param value the input value
    */
  def headersFrom(value: A): Seq[HttpHeader]
}

// $COVERAGE-OFF$
object HttpResponseFields {

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f function from A to StatusCode
    * @tparam A type parameter to map to HttpResponseFields
    */
  def apply[A](f: A => StatusCode): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)
      override def headersFrom(value: A): Seq[HttpHeader] = Seq.empty
    }

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f function from A to a tuple StatusCode and Seq[HttpHeader]
    * @tparam A type parameter to map to HttpResponseFields
    */
  def fromStatusAndHeaders[A](f: A => (StatusCode, Seq[HttpHeader])): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)._1
      override def headersFrom(value: A): Seq[HttpHeader] = f(value)._2
    }

  implicit val responseFieldsPermissions: HttpResponseFields[PermissionsRejection] =
    HttpResponseFields {
      case PermissionsRejection.IncorrectRev(_, _)     => StatusCodes.Conflict
      case PermissionsRejection.RevisionNotFound(_, _) => StatusCodes.NotFound
      case _                                           => StatusCodes.BadRequest
    }

  implicit val responseFieldsAcls: HttpResponseFields[AclRejection] =
    HttpResponseFields {
      case AclRejection.AclNotFound(_)            => StatusCodes.NotFound
      case AclRejection.IncorrectRev(_, _, _)     => StatusCodes.Conflict
      case AclRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
      case AclRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case _                                      => StatusCodes.BadRequest
    }

  implicit val responseFieldsIdentities: HttpResponseFields[TokenRejection] =
    HttpResponseFields { _ =>
      StatusCodes.Unauthorized
    }

  implicit val responseFieldsRealms: HttpResponseFields[RealmRejection] =
    HttpResponseFields {
      case RealmRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
      case RealmRejection.RealmNotFound(_)          => StatusCodes.NotFound
      case RealmRejection.IncorrectRev(_, _)        => StatusCodes.Conflict
      case RealmRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case _                                        => StatusCodes.BadRequest
    }

  implicit val responseFieldsOrganizations: HttpResponseFields[OrganizationRejection] =
    HttpResponseFields {
      case OrganizationRejection.OrganizationNotFound(_)   => StatusCodes.NotFound
      case OrganizationRejection.IncorrectRev(_, _)        => StatusCodes.Conflict
      case OrganizationRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
      case OrganizationRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case _                                               => StatusCodes.BadRequest
    }

  implicit val responseFieldsProjects: HttpResponseFields[ProjectRejection] =
    HttpResponseFields {
      case ProjectRejection.RevisionNotFound(_, _)  => StatusCodes.NotFound
      case ProjectRejection.ProjectNotFound(_)      => StatusCodes.NotFound
      case ProjectRejection.OrganizationNotFound(_) => StatusCodes.NotFound
      case ProjectRejection.IncorrectRev(_, _)      => StatusCodes.Conflict
      //case ProjectRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case _                                        => StatusCodes.BadRequest
    }
}
// $COVERAGE-ON$
