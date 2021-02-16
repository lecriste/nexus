package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.ConstantStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.sourcing.config.{AggregateConfig, ExternalIndexingConfig, PersistProgressConfig}
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import ch.epfl.bluebrain.nexus.sourcing.{config, SnapshotStrategyConfig}
import org.scalatest.OptionValues

import scala.concurrent.duration._

trait ConfigFixtures extends OptionValues {

  def neverStop     = StopStrategyConfig(None, None)
  def neverSnapShot = SnapshotStrategyConfig(None, None, None).value

  def aggregate(implicit typedSystem: ActorSystem[Nothing]): AggregateConfig =
    config.AggregateConfig(stopStrategy = neverStop, snapshotStrategy = neverSnapShot, processor = processor)

  def processor(implicit typedSystem: ActorSystem[Nothing]): EventSourceProcessorConfig = EventSourceProcessorConfig(
    askTimeout = Timeout(6.seconds),
    evaluationMaxDuration = 5.second,
    evaluationExecutionContext = typedSystem.executionContext,
    stashSize = 100
  )

  def keyValueStore: KeyValueStoreConfig =
    KeyValueStoreConfig(
      askTimeout = 5.seconds,
      consistencyTimeout = 2.seconds,
      RetryStrategyConfig.ExponentialStrategyConfig(50.millis, 30.seconds, 20)
    )

  def cacheIndexing: CacheIndexingConfig =
    CacheIndexingConfig(1, RetryStrategyConfig.ConstantStrategyConfig(1.second, 10))

  def externalIndexing: ExternalIndexingConfig =
    config.ExternalIndexingConfig("prefix", 2, 100.millis, ConstantStrategyConfig(1.second, 10), persist)

  def persist: PersistProgressConfig = PersistProgressConfig(2, 20.millis)

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )

  def httpClientConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)

}
