package org.lasersonlab.zarr

import cats.data.Nested
import cats.{ Eval, Foldable, Traverse }
import hammerlab.option._
import org.lasersonlab.circe.EncoderK
import org.lasersonlab.ndarray.{ ArrayLike, Indices, UnfoldRange, Vector }
import org.lasersonlab.slist.{ Scannable, Size, Zip }
import org.lasersonlab.zarr.Compressor.Blosc
import org.lasersonlab.zarr.FillValue.Null
import org.lasersonlab.zarr.Order.C
import org.lasersonlab.zarr.array.metadata
import org.lasersonlab.zarr.circe.Json
import org.lasersonlab.zarr.dtype.DataType
import org.lasersonlab.zarr.io.{ Load, Save }
import org.lasersonlab.zarr.utils.{ ChunkSize, Idx }
import org.lasersonlab.zarr.utils.Idx.Long.CastException
import org.lasersonlab.{ zarr ⇒ z }
import shapeless.the

import scala.util.Try

/**
 * A Zarr N-dimensional array
 *
 * Storage of the ND-array of chunks, as well as the records in each chunk, are each a configurable type-param; see
 * companion-object for some convenient constructors
 *
 * Two constructors are provided in the companion object:
 *
 * - one that reads an [[Array]] from a [[Path directory path]]
 * - one that takes the elements (as well as shape and other metadata) as arguments
 *
 * The `convert` module contains yet another, which loads from an HDF5 file.
 *
 * TODO: experiment with Breeze vector/array for 1D/2D cases
 * TODO: auto-[[Save]] [[Vector]]s (utilizing an implicit chunk-size in bytes)
 * TODO: add a sensible toString
 */
sealed trait Array {
  /** Element type */
  type T

  /** Structure of this [[Array]]'s [[Shape "shape"]] (and, as a result, [[Index indices]] of individual elements) */
  type ShapeT[_]

  /**
   * "Index" data-type; typically [[Int]], but [[Long]] is also supported
   *
   * Note that the number of chunks in any dimension, as well as the size of each chunk in each dimension, must still be
   * an [[Int]]
   */
  type Idx

  /** Type of the "shape" of this [[Array]] */
  type Shape = ShapeT[Idx]

  /** Type of elements' indices; same as the [[Array]]'s [[Shape]] */
  type Index = Shape

  /** N-dimensional container for this [[Array]]'s "chunks" */
  type A[_]

  /** N-dimensional container that each "chunk" uses to store its [[T elements]] */
  type Chunk[_]

  /**
   * Useful evidences:
   *
   * - [[chunks the chunks array]] is [[Traverse traversable]]
   * - [[ShapeT the "shape" type]] is [[Traverse traversable]]
   * - [[Chunk chunks]] are [[Foldable]]
   */
  implicit val traverseA    : Traverse[     A]
  implicit val traverseShape: Traverse[ShapeT]
  implicit val foldableChunk: Foldable[ Chunk]

  /**
   * Widen to an [[Array.T]], so that [[cats]] typeclasses (e.g. [[Array.foldableT]]) can be picked up, and
   * corresponding syntax used, e.g.
   *
   * {{{
   * arr.t.toList
   * }}}
   *
   * This is necessary due to some unification limitations relating to the various aliases ([[Array.?]],
   * [[Array.Aux]], [[Array.*?]], etc.) used to specify different subsets of an [[Array]]'s dependent types' that are
   * known at a given call-site
   */
  def t: Array.T[T] = this match { case a: Array.T[T] ⇒ a }

  /**
   * Short-hand for imbuing this [[Array]] with an element type at runtime, e.g. in the case where it was loaded without
   * that type having been known ahead of time
   */
  def as[T]: Array.Aux[this.ShapeT, this.Idx, this.A, this.Chunk, T] =
    this
      .asInstanceOf[
        Array.Aux[
          this.ShapeT,
          this.Idx,
          this.A,
          this.Chunk,
          T
        ]
      ]

  val       shape: ShapeT[Dimension[Idx]]
  def chunkRanges: ShapeT[    Chunk.Idx ] = shape.map { _.range }

  /**
   * Random-indexing operation
   */
  def apply(idx: Index): T  // TODO: support exceptions: IndexOutOfBounds, IO, etc.

  val metadata: Metadata[ShapeT, Idx, T]

  /**
   * All the [[Array]]'s data lives here: an N-dimensional array of [[Chunk chunks]] that contain elements of type [[T]]
   */
  val chunks: A[Chunk[T]]

  // TODO: make this type-parameterizable, and validate accordingly during JSON-parsing
  val attrs: Option[Attrs]

  def foldLeft[B](b: B)(f: (B, T) ⇒ B): B =
    chunks
      .foldLeft(b) {
        (b, chunk) ⇒
          chunk
            .foldLeft(b) { f }
      }

  def foldRight[B](lb: Eval[B])(f: (T, Eval[B]) ⇒ Eval[B]): Eval[B] =
    chunks
      .foldRight(lb) {
        _.foldRight(_) { f }
      }
}

object Array
  extends array.load {

  /**
   * "Aux" aliases for various subsets of [[Array]]'s type-members that might be known / required in various contexts
   *
   * Everything is only instantiable via [[Aux]] to users outside this class.
   */
  sealed trait   T[                  _T] extends Array { type T = _T }                                   // element-type known, shape unknown
  sealed trait   ?[_ShapeT[_], _Idx    ] extends Array { type ShapeT[U] = _ShapeT[U]; type Idx = _Idx }  // element-type unknown, shape known
  sealed trait  Of[ ShapeT[_],  Idx, _T] extends ?[ShapeT,  Idx] with T[_T]
          type  *?[            _Idx    ] =       ?[  List, _Idx]                                         // element-type and number of dimensions unknown

  abstract class Aux[
    ShapeT[_],
       Idx   ,
        _A[_],
    _Chunk[_],
         T
  ]
  extends Of[ShapeT, Idx, T] {
    type     A[U] =     _A[U]
    type Chunk[U] = _Chunk[U]
  }

  /**
   * De-structure an [[Array]] into its [[Metadata]], [[Attrs]], and [[Array.chunks]] members, preserving whatever is
   * known about their types
   */
  def unapply(a: Array):
    Option[
      (
        Metadata[a.ShapeT, a.Idx, a.T],
        Option[Attrs],
        a.A[a.Chunk[a.T]]
      )
    ] =
    Some(
      (
        a.metadata,
        a.attrs,
        a.chunks
      )
    )

  /**
   * Constructor for "in-line" / in-memory creation of [[Array]]s
   *
   * The [[_ShapeT shape]], [[_T elements]], and an implicit [[DataType]] are the only required parameters
   *
   * Many other parameters can be specified, either explicitly / by-name in the first parameter list, or via implicit
   * instances (the former overrides the latter)
   *
   * The second parameter (in the first parameter list), `chunkSize`, may also be used positionally; if not specified,
   * it defaults to the `shape` (first parameter), resulting in an [[Array]] that is one chunk
   */
  def apply[
    _ShapeT[_]
    : Scannable
    : Size
    : Traverse
    : UnfoldRange
    : Zip,
    _T
  ](
         shape:        _ShapeT[Int]        ,
     chunkSize: Opt[   _ShapeT[Int]] = None,
        _attrs: Opt[      Json     ] = None,
         dtype: Opt[  DataType[ _T]] = None,
    compressor: Opt[Compressor     ] = None,
         order: Opt[     Order     ] = None,
    fill_value: Opt[ FillValue[ _T]] = None
  )(
    _elems: _T*
  )(
    implicit
       _datatype:          DataType[_T],
     _compressor:        Compressor     = Blosc(),
          _order:             Order     = C,
     _fill_value:         FillValue[_T] = Null,
     zarr_format:            Format     = Format.`2`,
         filters: Option[Seq[Filter]]   = None,
      _chunkSize:         ChunkSize     = 32 MB
  ):
    Aux[
      _ShapeT,
      Int,
      Vector[_ShapeT, ?],
      Vector[_ShapeT, ?],
      _T
    ] = {
    val _shape = shape  // work around shadowing, but keep parameter-name nice
    new Aux[
      _ShapeT,
      Int,
      Vector[_ShapeT, ?],
      Vector[_ShapeT, ?],
      _T
    ] {

      val traverseShape: Traverse[ShapeT] = Traverse[_ShapeT]
      val traverseA    : Traverse[     A] = Vector.traverse
      val foldableChunk: Foldable[ Chunk] = Vector.traverse

      val datatype = dtype.getOrElse(_datatype)

      val chunkShape =
        chunkSize
          .getOrElse {
            // If an explicit chunkSize isn't passed, chunk along the first axis, taking as many "rows" as still allow
            // chunks to be ≤ the implicit `ChunkSize` value (which defaults to 32MB)
            val bytes = _chunkSize.size
            val rowElems = _shape.toList.tail.product
            val rowSize = rowElems * datatype.size
            val rowsPerChunk =
              math.max(
                1,
                bytes / rowSize
              )

            _shape
              .mapWithIndex {
                (s, i) ⇒
                  if (i == 0)
                    math.min(s, rowsPerChunk)
                  else
                    s
              }
          }

      override val shape: ShapeT[Dimension[Idx]] =
        _shape
          .zip(chunkShape)
          .map {
            case (shape, chunk) ⇒
              Dimension.int(shape, chunk)
          }

      val elems = Vector[_ShapeT, _T](_shape, _elems: _*)

      override def apply(idx: Index): T = elems(idx)

      override val metadata: Metadata[ShapeT, Idx, T] =
        Metadata(
                shape = shape,
                dtype =   datatype,
           compressor = compressor.getOrElse(_compressor),
                order =      order.getOrElse(     _order),
           fill_value = fill_value.getOrElse(_fill_value),
          zarr_format = zarr_format,
              filters = filters
        )

      val indices = the[Indices[A, ShapeT]]
      override val chunks: A[Chunk[T]] = {
        indices(chunkRanges)
          .map {
            chunkIdx ⇒
              val start :: size :: end :: ⊥ =
                chunkShape
                  .zip(chunkIdx, _shape)
                  .map {
                    case (chunkSize, idx, size) ⇒
                      val start = chunkSize * idx
                      val end = (chunkSize * (idx + 1)) min size
                      start :: (end - start) :: end :: ⊥
                  }
                  .sequence

              indices(size)
                .map {
                  offset ⇒
                    apply(
                      start
                        .zip(offset)
                        .map {
                          case (start,  offset ) ⇒
                                start + offset }
                    )
                }
          }
      }

      override val attrs: Option[Attrs] = _attrs.map(Attrs(_))
    }
  }

  /**
   * Implement [[Foldable]] on an [[Array]] identified only by its element type; part of working around / mitigating
   * https://github.com/scala/bug/issues/11169.
   *
   * [[Array.foldLeft foldLeft]] and [[Array.foldRight foldRight]] are defined directly on [[Array]], but this can still
   * be useful in contexts based around Cats typeclasses
   */
  implicit val foldableT: Foldable[Array.T] =
    new Foldable[Array.T] {
      @inline def foldLeft [A, B](fa: T[A],  b:      B )(f: (B,      A ) ⇒      B ):      B  = fa.foldLeft ( b)(f)
      @inline def foldRight[A, B](fa: T[A], lb: Eval[B])(f: (A, Eval[B]) ⇒ Eval[B]): Eval[B] = fa.foldRight(lb)(f)
    }

  implicit def loadArr[
    Shape[_]
    : VectorEvidence,
    T
    :  DataType.Decoder
    : FillValue.Decoder
  ](
    implicit
    idx: Idx
  ):
    Load[
      Of[Shape, idx.T, T]
    ] =
    new Load[Of[Shape, idx.T, T]] {
      def apply(dir: Path): Exception | Of[Shape, idx.T, T] = Array[Shape, T](dir)
    }

  implicit def saveOf[
    ShapeT[_]
    : EncoderK
    : Scannable,
    T
  ](implicit idx: Idx):
    Save[
      Of[ShapeT, idx.T, T]
    ] =
    Save.as(a ⇒ a: ?[ShapeT, idx.T])

  implicit def save_?[
    _ShapeT[_]
    : EncoderK
    : Scannable,
    _Idx: Idx.T
  ]:
  Save[
    Array.?[_ShapeT, _Idx]
  ] =
    new Save[Array.?[_ShapeT, _Idx]] {
      def direct(
        _a: Array.?[_ShapeT, _Idx],
        dir: Path
      ):
        Throwable |
        Unit
      = {
        // work around https://github.com/scala/bug/issues/11086; method-params incorrectly considered "unstable"
        val a = _a
        import a._

        def chunkResults: Throwable | Unit = {
          val (_, chunkStrides) = chunkRanges.scanRight(1)(_ * _)
          val chunkSize =
            shape
              .foldLeft(1) {
                _ * _.chunk
              }

          a
            .chunks
            .mapWithIndex {
              (chunk, int) ⇒
                // traverse chunk-strides to convert linear/integer index to structured N-dimensional index
                val idx =
                  chunkStrides
                    .scanLeft_→(
                      (
                        int,  // output
                        int   // remaining
                      )
                    ) {
                      case (
                        (
                          _,
                          remaining
                        ),
                        stride
                      ) ⇒
                        (
                          remaining / stride,
                          remaining % stride
                        )
                    }
                    .map { _._1 }

                val path = dir / Key(idx)
                Try {
                  import java.nio.ByteBuffer._
                  val datatype = a.metadata.dtype
                  val buffer = allocate(datatype.size * chunkSize)
                  chunk
                    .foldLeft(()) {
                      (_, elem) ⇒
                        datatype(buffer, elem)

                        ()
                    }

                  val os =
                    a.metadata.compressor(
                      path.outputStream(mkdirs = true),
                      datatype.size
                    )

                  os.write(buffer.array())
                  os.close()
                }
                .toEither
            }
            .sequence
            .map { _ ⇒ () }
        }

        for {
          _ ← a.metadata.save(dir)
          _ ← a.   attrs.save(dir)
          _ ← chunkResults
        } yield
          ()
      }
    }
}
