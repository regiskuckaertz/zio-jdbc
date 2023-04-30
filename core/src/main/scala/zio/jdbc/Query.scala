package zio.jdbc

import zio._
import zio.stream._

final class Query[+A](val sql: SqlFragment0, val decode: ZResultSet => A) {

  def as[B](implicit decoder: JdbcDecoder[B]): Query[B] =
    new Query(sql, (rs: ZResultSet) => decoder.unsafeDecode(rs.resultSet))

  def withDecode[B](f: ZResultSet => B): Query[B] =
    new Query(sql, f)

  def map[B](f: A => B): Query[B] =
    new Query(sql, rs => f(decode(rs)))

  def selectAll(implicit ev: IsSqlFragment[A]): ZIO[ZConnection, Throwable, Chunk[A]] =
    ZIO.scoped(for {
      zrs   <- executeQuery
      chunk <- ZIO.attempt {
                 val builder = ChunkBuilder.make[A]()
                 while (zrs.next())
                   builder += decode(zrs)
                 builder.result()
               }
    } yield chunk)

  def selectOne(implicit ev: IsSqlFragment[A]): ZIO[ZConnection, Throwable, Option[A]] =
    ZIO.scoped(for {
      zrs    <- executeQuery
      option <- ZIO.attempt {
                  if (zrs.next()) Some(decode(zrs)) else None
                }
    } yield option)

  def selectStream(implicit ev: IsSqlFragment[A]): ZStream[ZConnection, Throwable, A] =
    ZStream.unwrapScoped {
      for {
        zrs   <- executeQuery
        stream = ZStream.repeatZIOOption {
                   ZIO
                     .suspend(if (zrs.next()) ZIO.attempt(Some(decode(zrs))) else ZIO.none)
                     .mapError(Option(_))
                     .flatMap {
                       case None    => ZIO.fail(None)
                       case Some(v) => ZIO.succeed(v)
                     }
                 }
      } yield stream
    }

  private def executeQuery: ZIO[Scope with ZConnection, Throwable, ZResultSet] = for {
    connection <- ZIO.service[ZConnection]
    zrs        <- connection.executeSqlWith0(sql) { ps =>
                    ZIO.acquireRelease {
                      ZIO.attempt(ZResultSet(ps.executeQuery()))
                    }(_.close)
                  }
  } yield zrs

}
