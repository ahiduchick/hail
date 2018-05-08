package is.hail.linalg


import breeze.linalg.{diag, DenseMatrix => BDM}
import is.hail.{SparkSuite, TestUtils}
import is.hail.check.Arbitrary._
import is.hail.check.Prop._
import is.hail.check.Gen._
import is.hail.check._
import is.hail.linalg.BlockMatrix.ops._
import is.hail.expr.types._
import is.hail.table.Table
import is.hail.utils._
import org.apache.spark.sql.Row
import org.testng.annotations.Test

import scala.language.implicitConversions

class BlockMatrixSuite extends SparkSuite {

  // row major
  def toLM(nRows: Int, nCols: Int, data: Array[Double]): BDM[Double] =
    new BDM(nRows, nCols, data, 0, nCols, isTranspose = true)

  def toBM(nRows: Int, nCols: Int, data: Array[Double]): BlockMatrix =
    toBM(new BDM(nRows, nCols, data, 0, nRows, true))

  def toBM(rows: Seq[Array[Double]]): BlockMatrix =
    toBM(rows, BlockMatrix.defaultBlockSize)

  def toBM(rows: Seq[Array[Double]], blockSize: Int): BlockMatrix = {
    val n = rows.length
    val m = if (n == 0) 0 else rows(0).length

    BlockMatrix.fromBreezeMatrix(sc, new BDM[Double](m, n, rows.flatten.toArray).t, blockSize)
  }

  def toBM(lm: BDM[Double]): BlockMatrix =
    toBM(lm, BlockMatrix.defaultBlockSize)

  def toBM(lm: BDM[Double], blockSize: Int): BlockMatrix =
    BlockMatrix.fromBreezeMatrix(sc, lm, blockSize)

  private val defaultBlockSize = choose(1, 1 << 6)
  private val defaultDims = nonEmptySquareOfAreaAtMostSize
  private val defaultElement = arbitrary[Double]

  def blockMatrixGen(
    blockSize: Gen[Int] = defaultBlockSize,
    dims: Gen[(Int, Int)] = defaultDims,
    element: Gen[Double] = defaultElement
  ): Gen[BlockMatrix] = for {
    blockSize <- blockSize
    (nRows, nCols) <- dims
    arrays <- buildableOfN[Seq](nRows, buildableOfN[Array](nCols, element))
    m = toBM(arrays, blockSize)
  } yield m

  def squareBlockMatrixGen(
    element: Gen[Double] = defaultElement
  ): Gen[BlockMatrix] = blockMatrixGen(
    blockSize = interestingPosInt.map(math.sqrt(_).toInt),
    dims = for {
      size <- size
      l <- interestingPosInt
      s = math.sqrt(math.min(l, size)).toInt
    } yield (s, s),
    element = element
  )

  def twoMultipliableBlockMatrices(element: Gen[Double] = defaultElement): Gen[(BlockMatrix, BlockMatrix)] = for {
    Array(nRows, innerDim, nCols) <- nonEmptyNCubeOfVolumeAtMostSize(3)
    blockSize <- interestingPosInt.map(math.pow(_, 1.0 / 3.0).toInt)
    l <- blockMatrixGen(const(blockSize), const(nRows -> innerDim), element)
    r <- blockMatrixGen(const(blockSize), const(innerDim -> nCols), element)
  } yield (l, r)

  implicit val arbitraryBlockMatrix =
    Arbitrary(blockMatrixGen())

  private val defaultRelTolerance = 1e-14

  private def sameDoubleMatrixNaNEqualsNaN(x: BDM[Double], y: BDM[Double], relTolerance: Double = defaultRelTolerance): Boolean =
    findDoubleMatrixMismatchNaNEqualsNaN(x, y, relTolerance) match {
      case Some(_) => false
      case None => true
    }

  private def findDoubleMatrixMismatchNaNEqualsNaN(x: BDM[Double], y: BDM[Double], relTolerance: Double = defaultRelTolerance): Option[(Int, Int)] = {
    assert(x.rows == y.rows && x.cols == y.cols,
      s"dimension mismatch: ${ x.rows } x ${ x.cols } vs ${ y.rows } x ${ y.cols }")
    var j = 0
    while (j < x.cols) {
      var i = 0
      while (i < x.rows) {
        if (D_==(x(i, j) - y(i, j), relTolerance) && !(x(i, j).isNaN && y(i, j).isNaN)) {
          println(x.toString(1000, 1000))
          println(y.toString(1000, 1000))
          println(s"inequality found at ($i, $j): ${ x(i, j) } and ${ y(i, j) }")
          return Some((i, j))
        }
        i += 1
      }
      j += 1
    }
    None
  }

  @Test
  def pointwiseSubtractCorrect() {
    val m = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val expected = toLM(4, 4, Array[Double](
      0, -3, -6, -9,
      3, 0, -3, -6,
      6, 3, 0, -3,
      9, 6, 3, 0))

    val actual = (m - m.T).toBreezeMatrix()
    assert(actual == expected)
  }

  @Test
  def multiplyByLocalMatrix() {
    val ll = toLM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))
    val l = toBM(ll)

    val lr = toLM(4, 1, Array[Double](
      1,
      2,
      3,
      4))

    assert(ll * lr === l.dot(lr).toBreezeMatrix())
  }

  @Test
  def randomMultiplyByLocalMatrix() {
    forAll(twoMultipliableDenseMatrices[Double]()) { case (ll, lr) =>
      val l = toBM(ll)
      sameDoubleMatrixNaNEqualsNaN(ll * lr, l.dot(lr).toBreezeMatrix())
    }.check()
  }

  @Test
  def multiplySameAsBreeze() {
    def randomLm(n: Int, m: Int) = denseMatrix[Double](n, m)

    forAll(randomLm(4, 4), randomLm(4, 4)) { (ll, lr) =>
      val l = toBM(ll, 2)
      val r = toBM(lr, 2)

      sameDoubleMatrixNaNEqualsNaN(l.dot(r).toBreezeMatrix(), ll * lr)
    }.check()

    forAll(randomLm(9, 9), randomLm(9, 9)) { (ll, lr) =>
      val l = toBM(ll, 3)
      val r = toBM(lr, 3)

      sameDoubleMatrixNaNEqualsNaN(l.dot(r).toBreezeMatrix(), ll * lr)
    }.check()

    forAll(randomLm(9, 9), randomLm(9, 9)) { (ll, lr) =>
      val l = toBM(ll, 2)
      val r = toBM(lr, 2)

      sameDoubleMatrixNaNEqualsNaN(l.dot(r).toBreezeMatrix(), ll * lr)
    }.check()

    forAll(randomLm(2, 10), randomLm(10, 2)) { (ll, lr) =>
      val l = toBM(ll, 3)
      val r = toBM(lr, 3)

      sameDoubleMatrixNaNEqualsNaN(l.dot(r).toBreezeMatrix(), ll * lr)
    }.check()

    forAll(twoMultipliableDenseMatrices[Double](), interestingPosInt) { case ((ll, lr), blockSize) =>
      val l = toBM(ll, blockSize)
      val r = toBM(lr, blockSize)

      sameDoubleMatrixNaNEqualsNaN(l.dot(r).toBreezeMatrix(), ll * lr)
    }.check()
  }

  @Test
  def multiplySameAsBreezeRandomized() {
    forAll(twoMultipliableBlockMatrices(nonExtremeDouble)) { case (l: BlockMatrix, r: BlockMatrix) =>
      val actual = l.dot(r).toBreezeMatrix()
      val expected = l.toBreezeMatrix() * r.toBreezeMatrix()

      findDoubleMatrixMismatchNaNEqualsNaN(actual, expected) match {
        case Some((i, j)) =>
          println(s"blockSize: ${ l.blockSize }")
          println(s"${ l.toBreezeMatrix() }")
          println(s"${ r.toBreezeMatrix() }")
          println(s"row: ${ l.toBreezeMatrix()(i, ::) }")
          println(s"col: ${ r.toBreezeMatrix()(::, j) }")
          false
        case None =>
          true
      }
    }.check()
  }

  @Test
  def rowwiseMultiplication() {
    val l = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val v = Array[Double](1, 2, 3, 4)

    val result = toLM(4, 4, Array[Double](
      1, 4, 9, 16,
      5, 12, 21, 32,
      9, 20, 33, 48,
      13, 28, 45, 64))

    assert(l.rowVectorMul(v).toBreezeMatrix() == result)
  }

  @Test
  def rowwiseMultiplicationRandom() {
    val g = for {
      l <- blockMatrixGen()
      v <- buildableOfN[Array](l.nCols.toInt, arbitrary[Double])
    } yield (l, v)

    forAll(g) { case (l: BlockMatrix, v: Array[Double]) =>
      val actual = l.rowVectorMul(v).toBreezeMatrix()
      val repeatedR = (0 until l.nRows.toInt).flatMap(_ => v).toArray
      val repeatedRMatrix = new BDM(v.length, l.nRows.toInt, repeatedR).t
      val expected = l.toBreezeMatrix() *:* repeatedRMatrix

      sameDoubleMatrixNaNEqualsNaN(actual, expected)
    }.check()
  }

  @Test
  def colwiseMultiplication() {
    val l = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val v = Array[Double](1, 2, 3, 4)

    val result = toLM(4, 4, Array[Double](
      1, 2, 3, 4,
      10, 12, 14, 16,
      27, 30, 33, 36,
      52, 56, 60, 64))

    assert(l.colVectorMul(v).toBreezeMatrix() == result)
  }

  @Test
  def colwiseMultiplicationRandom() {
    val g = for {
      l <- blockMatrixGen()
      v <- buildableOfN[Array](l.nRows.toInt, arbitrary[Double])
    } yield (l, v)

    forAll(g) { case (l: BlockMatrix, v: Array[Double]) =>
      val actual = l.colVectorMul(v).toBreezeMatrix()
      val repeatedR = (0 until l.nCols.toInt).flatMap(_ => v).toArray
      val repeatedRMatrix = new BDM(v.length, l.nCols.toInt, repeatedR)
      val expected = l.toBreezeMatrix() *:* repeatedRMatrix

      if (sameDoubleMatrixNaNEqualsNaN(actual, expected))
        true
      else {
        println(s"${ l.toBreezeMatrix().toArray.toSeq }\n*\n${ v.toSeq }")
        false
      }
    }.check()
  }

  @Test
  def colwiseAddition() {
    val l = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val v = Array[Double](1, 2, 3, 4)

    val result = toLM(4, 4, Array[Double](
      2, 3, 4, 5,
      7, 8, 9, 10,
      12, 13, 14, 15,
      17, 18, 19, 20))

    assert(l.colVectorAdd(v).toBreezeMatrix() == result)
  }

  @Test
  def rowwiseAddition() {
    val l = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val v = Array[Double](1, 2, 3, 4)

    val result = toLM(4, 4, Array[Double](
      2, 4, 6, 8,
      6, 8, 10, 12,
      10, 12, 14, 16,
      14, 16, 18, 20))

    assert(l.rowVectorAdd(v).toBreezeMatrix() == result)
  }

  @Test
  def diagonalTestTiny() {
    val lm = toLM(3, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12))
    
    val m = toBM(lm, blockSize = 2)

    assert(m.diagonal().toSeq == Seq(1, 6, 11))
    assert(m.T.diagonal().toSeq == Seq(1, 6, 11))
    assert(m.dot(m.T).diagonal().toSeq == Seq(30, 174, 446))
  }

  @Test
  def diagonalTestRandomized() {
    forAll(squareBlockMatrixGen()) { (m: BlockMatrix) =>
      val lm = m.toBreezeMatrix()
      val diagonalLength = math.min(lm.rows, lm.cols)
      val diagonal = Array.tabulate(diagonalLength)(i => lm(i, i))

      if (m.diagonal().toSeq == diagonal.toSeq)
        true
      else {
        println(s"lm: $lm")
        println(s"${ m.diagonal().toSeq } != ${ diagonal.toSeq }")
        false
      }
    }.check()
  }

  @Test
  def fromLocalTest() {
    forAll(denseMatrix[Double]()) { lm =>
      assert(lm === BlockMatrix.fromBreezeMatrix(sc, lm, lm.rows + 1).toBreezeMatrix())
      assert(lm === BlockMatrix.fromBreezeMatrix(sc, lm, lm.rows).toBreezeMatrix())
      if (lm.rows > 1) {
        assert(lm === BlockMatrix.fromBreezeMatrix(sc, lm, lm.rows - 1).toBreezeMatrix())
        assert(lm === BlockMatrix.fromBreezeMatrix(sc, lm, math.sqrt(lm.rows).toInt).toBreezeMatrix())
      }
      true
    }.check()
  }

  @Test
  def readWriteIdentityTrivial() {
    val m = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val fname = tmpDir.createTempFile("test")
    m.write(fname)
    assert(m.toBreezeMatrix() == BlockMatrix.read(hc, fname).toBreezeMatrix())

    val fname2 = tmpDir.createTempFile("test2")
    m.write(fname2, forceRowMajor = true)
    assert(m.toBreezeMatrix() == BlockMatrix.read(hc, fname2).toBreezeMatrix())
  }

  @Test
  def readWriteIdentityTrivialTransposed() {
    val m = toBM(4, 4, Array[Double](
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val fname = tmpDir.createTempFile("test")
    m.T.write(fname)
    assert(m.T.toBreezeMatrix() == BlockMatrix.read(hc, fname).toBreezeMatrix())

    val fname2 = tmpDir.createTempFile("test2")
    m.T.write(fname2, forceRowMajor = true)
    assert(m.T.toBreezeMatrix() == BlockMatrix.read(hc, fname2).toBreezeMatrix())
  }

  @Test
  def readWriteIdentityRandom() {
    forAll(blockMatrixGen()) { (m: BlockMatrix) =>
      val fname = tmpDir.createTempFile("test")
      m.write(fname)
      assert(sameDoubleMatrixNaNEqualsNaN(m.toBreezeMatrix(), BlockMatrix.read(hc, fname).toBreezeMatrix()))
      true
    }.check()
  }

  @Test
  def transpose() {
    forAll(blockMatrixGen()) { (m: BlockMatrix) =>
      val transposed = m.toBreezeMatrix().t
      assert(transposed.rows == m.nCols)
      assert(transposed.cols == m.nRows)
      assert(transposed === m.T.toBreezeMatrix())
      true
    }.check()
  }

  @Test
  def doubleTransposeIsIdentity() {
    forAll(blockMatrixGen(element = nonExtremeDouble)) { (m: BlockMatrix) =>
      val mt = m.T.cache()
      val mtt = m.T.T.cache()
      assert(mtt.nRows == m.nRows)
      assert(mtt.nCols == m.nCols)
      assert(sameDoubleMatrixNaNEqualsNaN(mtt.toBreezeMatrix(), m.toBreezeMatrix()))
      assert(sameDoubleMatrixNaNEqualsNaN(mt.dot(mtt).toBreezeMatrix(), mt.dot(m).toBreezeMatrix()))
      true
    }.check()
  }

  @Test
  def cachedOpsOK() {
    forAll(twoMultipliableBlockMatrices(nonExtremeDouble)) { case (l: BlockMatrix, r: BlockMatrix) =>
      l.cache()
      r.cache()

      val actual = l.dot(r).toBreezeMatrix()
      val expected = l.toBreezeMatrix() * r.toBreezeMatrix()

      if (!sameDoubleMatrixNaNEqualsNaN(actual, expected)) {
        println(s"${ l.toBreezeMatrix() }")
        println(s"${ r.toBreezeMatrix() }")
        assert(false)
      }

      if (!sameDoubleMatrixNaNEqualsNaN(l.T.cache().T.toBreezeMatrix(), l.toBreezeMatrix())) {
        println(s"${ l.T.cache().T.toBreezeMatrix() }")
        println(s"${ l.toBreezeMatrix() }")
        assert(false)
      }

      true
    }.check()
  }

  @Test
  def toIRMToHBMIdentity() {
    forAll(blockMatrixGen()) { (m: BlockMatrix) =>
      val roundtrip = m.toIndexedRowMatrix().toHailBlockMatrix(m.blockSize)

      val roundtriplm = roundtrip.toBreezeMatrix()
      val lm = m.toBreezeMatrix()

      if (roundtriplm != lm) {
        println(roundtriplm)
        println(lm)
        assert(false)
      }

      true
    }.check()
  }

  @Test
  def map2RespectsTransposition() {
    val lm = toLM(4, 2, Array[Double](
      1, 2,
      3, 4,
      5, 6,
      7, 8))
    val lmt = toLM(2, 4, Array[Double](
      1, 3, 5, 7,
      2, 4, 6, 8))

    val m = toBM(lm)
    val mt = toBM(lmt)

    assert(m.map2(mt.T, _ + _).toBreezeMatrix() === lm + lm)
    assert(mt.T.map2(m, _ + _).toBreezeMatrix() === lm + lm, s"${ mt.toBreezeMatrix() }\n${ mt.T.toBreezeMatrix() }\n${ m.toBreezeMatrix() }")
  }

  @Test
  def map4RespectsTransposition() {
    val lm = toLM(4, 2, Array[Double](
      1, 2,
      3, 4,
      5, 6,
      7, 8))
    val lmt = toLM(2, 4, Array[Double](
      1, 3, 5, 7,
      2, 4, 6, 8))

    val m = toBM(lm)
    val mt = toBM(lmt)

    assert(m.map4(m, mt.T, mt.T.T.T, _ + _ + _ + _).toBreezeMatrix() === lm + lm + lm + lm)
    assert(mt.map4(mt, m.T, mt.T.T, _ + _ + _ + _).toBreezeMatrix() === lm.t + lm.t + lm.t + lm.t)
  }

  @Test
  def mapRespectsTransposition() {
    val lm = toLM(4, 2, Array[Double](
      1, 2,
      3, 4,
      5, 6,
      7, 8))
    val lmt = toLM(2, 4, Array[Double](
      1, 3, 5, 7,
      2, 4, 6, 8))

    val m = toBM(lm)
    val mt = toBM(lmt)

    assert(m.T.map(_ * 4).toBreezeMatrix() === lm.t.map(_ * 4))
    assert(m.T.T.map(_ * 4).toBreezeMatrix() === lm.map(_ * 4))
    assert(mt.T.map(_ * 4).toBreezeMatrix() === lm.map(_ * 4))
  }

  @Test
  def mapWithIndexRespectsTransposition() {
    val lm = toLM(4, 2, Array[Double](
      1, 2,
      3, 4,
      5, 6,
      7, 8))
    val lmt = toLM(2, 4, Array[Double](
      1, 3, 5, 7,
      2, 4, 6, 8))

    val m = toBM(lm)
    val mt = toBM(lmt)

    assert(m.T.mapWithIndex((_, _, x) => x * 4).toBreezeMatrix() === lm.t.map(_ * 4))
    assert(m.T.T.mapWithIndex((_, _, x) => x * 4).toBreezeMatrix() === lm.map(_ * 4))
    assert(mt.T.mapWithIndex((_, _, x) => x * 4).toBreezeMatrix() === lm.map(_ * 4))

    assert(m.T.mapWithIndex((i, j, x) => i * 10 + j + x).toBreezeMatrix() ===
      mt.mapWithIndex((i, j, x) => i * 10 + j + x).toBreezeMatrix())
    assert(m.T.mapWithIndex((i, j, x) => x + j * 2 + i + 1).toBreezeMatrix() ===
      lm.t + lm.t)
    assert(mt.mapWithIndex((i, j, x) => x + j * 2 + i + 1).toBreezeMatrix() ===
      lm.t + lm.t)
    assert(mt.T.mapWithIndex((i, j, x) => x + i * 2 + j + 1).toBreezeMatrix() ===
      lm + lm)
  }

  @Test
  def map2WithIndexRespectsTransposition() {
    val lm = toLM(4, 2, Array[Double](
      1, 2,
      3, 4,
      5, 6,
      7, 8))
    val lmt = toLM(2, 4, Array[Double](
      1, 3, 5, 7,
      2, 4, 6, 8))

    val m = toBM(lm)
    val mt = toBM(lmt)

    assert(m.map2WithIndex(mt.T, (_, _, x, y) => x + y).toBreezeMatrix() === lm + lm)
    assert(mt.map2WithIndex(m.T, (_, _, x, y) => x + y).toBreezeMatrix() === lm.t + lm.t)
    assert(mt.T.map2WithIndex(m, (_, _, x, y) => x + y).toBreezeMatrix() === lm + lm)
    assert(m.T.T.map2WithIndex(mt.T, (_, _, x, y) => x + y).toBreezeMatrix() === lm + lm)

    assert(m.T.map2WithIndex(mt, (i, j, x, y) => i * 10 + j + x + y).toBreezeMatrix() ===
      mt.map2WithIndex(m.T, (i, j, x, y) => i * 10 + j + x + y).toBreezeMatrix())
    assert(m.T.map2WithIndex(m.T, (i, j, x, y) => i * 10 + j + x + y).toBreezeMatrix() ===
      mt.map2WithIndex(mt, (i, j, x, y) => i * 10 + j + x + y).toBreezeMatrix())
    assert(m.T.map2WithIndex(mt, (i, j, x, y) => x + 2 * y + j * 2 + i + 1).toBreezeMatrix() ===
      4.0 * lm.t)
    assert(mt.map2WithIndex(m.T, (i, j, x, y) => x + 2 * y + j * 2 + i + 1).toBreezeMatrix() ===
      4.0 * lm.t)
    assert(mt.T.map2WithIndex(m.T.T, (i, j, x, y) => 3 * x + 5 * y + i * 2 + j + 1).toBreezeMatrix() ===
      9.0 * lm)
  }

  @Test
  def filterCols() {
    val lm = new BDM[Double](9, 10, (0 until 90).map(_.toDouble).toArray)

    for {blockSize <- Seq(1, 2, 3, 5, 10, 11)
    } {
      val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize)
      for {keep <- Seq(
        Array(0),
        Array(1),
        Array(9),
        Array(0, 3, 4, 5, 7),
        Array(1, 4, 5, 7, 8, 9),
        Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
      } {
        val filteredViaBlock = bm.filterCols(keep.map(_.toLong)).toBreezeMatrix()
        val filteredViaBreeze = lm(::, keep.toIndexedSeq).copy

        assert(filteredViaBlock === filteredViaBreeze)
      }
    }
  }

  @Test
  def filterColsTranspose() {
    val lm = new BDM[Double](9, 10, (0 until 90).map(_.toDouble).toArray)
    val lmt = lm.t

    for {blockSize <- Seq(2, 3)
    } {
      val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize).transpose()
      for {keep <- Seq(
        Array(0),
        Array(1, 4, 5, 7, 8),
        Array(0, 1, 2, 3, 4, 5, 6, 7, 8))
      } {
        val filteredViaBlock = bm.filterCols(keep.map(_.toLong)).toBreezeMatrix()
        val filteredViaBreeze = lmt(::, keep.toIndexedSeq).copy

        assert(filteredViaBlock === filteredViaBreeze)
      }
    }
  }

  @Test
  def filterRows() {
    val lm = new BDM[Double](9, 10, (0 until 90).map(_.toDouble).toArray)

    for {blockSize <- Seq(2, 3)
    } {
      val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize)
      for {keep <- Seq(
        Array(0),
        Array(1, 4, 5, 7, 8),
        Array(0, 1, 2, 3, 4, 5, 6, 7, 8))
      } {
        val filteredViaBlock = bm.filterRows(keep.map(_.toLong)).toBreezeMatrix()
        val filteredViaBreeze = lm(keep.toIndexedSeq, ::).copy

        assert(filteredViaBlock === filteredViaBreeze)
      }
    }
  }

  @Test
  def filterSymmetric() {
    val lm = new BDM[Double](10, 10, (0 until 100).map(_.toDouble).toArray)

    for {blockSize <- Seq(1, 2, 3, 5, 10, 11)
    } {
      val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize)
      for {keep <- Seq(
        Array(0),
        Array(1),
        Array(9),
        Array(0, 3, 4, 5, 7),
        Array(1, 4, 5, 7, 8, 9),
        Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
      } {
        val filteredViaBlock = bm.filter(keep.map(_.toLong), keep.map(_.toLong)).toBreezeMatrix()
        val filteredViaBreeze = lm(keep.toIndexedSeq, keep.toIndexedSeq).copy

        assert(filteredViaBlock === filteredViaBreeze)
      }
    }
  }

  @Test
  def filter() {
    val lm = new BDM[Double](9, 10, (0 until 90).map(_.toDouble).toArray)

    for {blockSize <- Seq(1, 2, 3, 5, 10, 11)
    } {
      val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize)
      for {
        keepRows <- Seq(
          Array(1),
          Array(0, 3, 4, 5, 7),
          Array(0, 1, 2, 3, 4, 5, 6, 7, 8))
        keepCols <- Seq(
          Array(2),
          Array(1, 4, 5, 7, 8, 9),
          Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
      } {
        val filteredViaBlock = bm.filter(keepRows.map(_.toLong), keepCols.map(_.toLong)).toBreezeMatrix()
        val filteredViaBreeze = lm(keepRows.toIndexedSeq, keepCols.toIndexedSeq).copy

        assert(filteredViaBlock === filteredViaBreeze)
      }
    }
  }

  @Test
  def writeLocalAsBlockTest() {
    val lm = new BDM[Double](10, 10, (0 until 100).map(_.toDouble).toArray)

    for {blockSize <- Seq(1, 2, 3, 5, 10, 11)} {
      val fname = tmpDir.createTempFile("test")
      lm.writeBlockMatrix(hc, fname, blockSize)
      assert(lm === BlockMatrix.read(hc, fname).toBreezeMatrix())
    }
  }

  @Test
  def randomTest() {
    var lm1 = BlockMatrix.random(hc, 5, 10, 2, seed = 1, gaussian = false).toBreezeMatrix()
    var lm2 = BlockMatrix.random(hc, 5, 10, 2, seed = 1, gaussian = false).toBreezeMatrix()
    var lm3 = BlockMatrix.random(hc, 5, 10, 2, seed = 2, gaussian = false).toBreezeMatrix()

    assert(lm1 === lm2)
    assert(lm1 !== lm3)
    assert(lm1.data.forall(x => x >= 0 && x <= 1))

    lm1 = BlockMatrix.random(hc, 5, 10, 2, seed = 1, gaussian = true).toBreezeMatrix()
    lm2 = BlockMatrix.random(hc, 5, 10, 2, seed = 1, gaussian = true).toBreezeMatrix()
    lm3 = BlockMatrix.random(hc, 5, 10, 2, seed = 2, gaussian = true).toBreezeMatrix()

    assert(lm1 === lm2)
    assert(lm1 !== lm3)
  }

  @Test
  def testEntriesTable(): Unit = {
    val data = (0 until 90).map(_.toDouble).toArray
    val lm = new BDM[Double](9, 10, data)
    val expectedEntries = data.map(x => ((x % 9).toLong, (x / 9).toLong, x)).toSet
    val expectedSignature = TStruct("i" -> TInt64Optional, "j" -> TInt64Optional, "entry" -> TFloat64Optional)

    for {blockSize <- Seq(1, 4, 10)} {
      val entriesTable = toBM(lm, blockSize).entriesTable(hc)
      val entries = entriesTable.collect().map(row => (row.get(0), row.get(1), row.get(2))).toSet
      // block size affects order of rows in table, but sets will be the same
      assert(entries === expectedEntries)
      assert(entriesTable.signature === expectedSignature)
    }
  }

  @Test
  def testEntriesTableWhenKeepingOnlySomeBlocks(): Unit = {
    val data = (0 until 50).map(_.toDouble).toArray
    val lm = new BDM[Double](5, 10, data)
    val bm = toBM(lm, blockSize = 2)
    
    assert(bm.filterBlocks(Array(0, 1, 6)).entriesTable(hc).collect().map(r => r.get(2).asInstanceOf[Double]) sameElements
      Array(0, 5, 1, 6, 2, 7, 3, 8, 20, 25, 21, 26).map(_.toDouble))
  }

  @Test
  def testPowSqrt(): Unit = {
    val lm = new BDM[Double](2, 3, Array(0.0, 1.0, 4.0, 9.0, 16.0, 25.0))
    val bm = BlockMatrix.fromBreezeMatrix(sc, lm, blockSize = 2)
    val expected = new BDM[Double](2, 3, Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    
    TestUtils.assertMatrixEqualityDouble(bm.pow(0.0).toBreezeMatrix(), BDM.fill(2, 3)(1.0))
    TestUtils.assertMatrixEqualityDouble(bm.pow(0.5).toBreezeMatrix(), expected)
    TestUtils.assertMatrixEqualityDouble(bm.sqrt().toBreezeMatrix(), expected)
  }

  @Test def testFilteredEntriesTable() {
    val rows = IndexedSeq[(String, Int)](("X", 5), ("X", 7), ("X", 13), ("X", 14), ("X", 17),
      ("X", 65), ("X", 70), ("X", 73), ("Y", 74), ("Y", 75), ("Y", 200), ("Y", 300))
      .map { case (contig, pos) => Row(contig, pos) }
    val tbl = Table.parallelize(hc, rows, TStruct("contig" -> TString(), "pos" -> TInt32()), None, None)
    
    val nRows = tbl.count().toInt
    val bm = BlockMatrix.fromBreezeMatrix(sc, BDM.zeros(nRows, nRows), blockSize = 1)
    
    val entriesTable = bm.filteredEntriesTable(tbl.keyBy("contig"), radius = 10, includeDiagonal = false)

    val expectedRows = IndexedSeq[(Long, Long)]((0, 1), (0, 2), (1, 2), (0, 3), (1, 3), (2, 3), (1, 4), (2, 4), (3, 4),
      (5, 6), (5, 7), (6, 7), (8, 9)).map { case (i, j) => Row(i, j) }
    val expectedTable = Table.parallelize(hc, expectedRows, TStruct("i" -> TInt64(), "j" -> TInt64()),
      None, None)

    assert(entriesTable.select("{i: row.i, j: row.j}").same(expectedTable))
  }
  
  def filteredEquals(bm1: BlockMatrix, bm2: BlockMatrix): Boolean =
    bm1.blocks.collect() sameElements bm2.blocks.collect()

  @Test
  def testFilterBlocks() {
    val lm = toLM(4, 4, Array(
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))
        
    val bm = toBM(lm, blockSize = 2)

    val keepArray = Array(
      Array.empty[Int],
      Array(0),
      Array(1, 3),
      Array(2, 3),
      Array(1, 2, 3),
      Array(0, 1, 2, 3))

    val localBlocks = Array(lm(0 to 1, 0 to 1), lm(2 to 3, 0 to 1), lm(0 to 1, 2 to 3), lm(2 to 3, 2 to 3))
     
    for { keep <- keepArray } {
      val fbm = bm.filterBlocks(keep)
      
      assert(fbm.blocks.count() == keep.length)
      assert(fbm.blocks.collect().forall { case ((i, j), block) =>
        block == localBlocks(fbm.gp.coordinatesBlock(i, j)) } )
    }
    
    val bm0 = bm.filterBlocks(Array(0)).cache()
    val bm13 = bm.filterBlocks(Array(1, 3)).cache()
    
    // test multiple block filters
    assert(filteredEquals(bm13, bm13.filterBlocks(Array(1, 3))))
    assert(filteredEquals(bm13, bm.filterBlocks(Array(1, 2, 3)).filterBlocks(Array(0, 1, 3))))
    assert(filteredEquals(bm13, bm13.filterBlocks(Array(0, 1, 2, 3))))
    assert(filteredEquals(bm.filterBlocks(Array(1)),
      bm.filterBlocks(Array(1, 2, 3)).filterBlocks(Array(0, 1, 2)).filterBlocks(Array(0, 1, 3))))
    
    val notSupported: String = "not supported for block-filtered block matrices"
    
    // filter not supported
    TestUtils.interceptFatal(notSupported) { bm0.filter(Array(0), Array(0)) }
    TestUtils.interceptFatal(notSupported) { bm0.filterRows(Array(0)) }
    TestUtils.interceptFatal(notSupported) { bm0.filterCols(Array(0)) }
  }

  
  @Test
  def testSparseBlockMatrixIO() {
    val lm = toLM(4, 4, Array(
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))
    
    val bm = toBM(lm, blockSize = 2)

    val keepArray = Array(
      Array.empty[Int],
      Array(0),
      Array(1, 3),
      Array(2, 3),
      Array(1, 2, 3),
      Array(0, 1, 2, 3))
    
    val lm_zero = BDM.zeros[Double](2, 2)

    def filterBlocks(keep: Array[Int]): BDM[Double] = {
      val flm = lm.copy
      (0 to 3).diff(keep).foreach { i =>
        val r = 2 * (i % 2)
        val c = 2 * (i / 2)
        flm(r to r + 1, c to c + 1) := lm_zero
      }
      flm
    }

    // test toBlockMatrix, toIndexedRowMatrix, toRowMatrix, read/write identity
    for { keep <- keepArray } {
      val fbm = bm.filterBlocks(keep)
      val flm = filterBlocks(keep)
      
      assert(fbm.toBreezeMatrix() === flm)

      assert(flm === fbm.toIndexedRowMatrix().toHailBlockMatrix().toBreezeMatrix())
      
      val fname = tmpDir.createTempFile("test")
      fbm.write(fname, forceRowMajor = true)
      
      assert(RowMatrix.readBlockMatrix(hc, fname, Some(3)).toBreezeMatrix() === flm)

      assert(filteredEquals(fbm, BlockMatrix.read(hc, fname)))
    }
    
    val bm0 = bm.filterBlocks(Array(0))
    
    val notSupported: String = "not supported for block-filtered block matrices"
    
    // filter not supported
    TestUtils.interceptFatal(notSupported) { bm0.filter(Array(0), Array(0)) }
    TestUtils.interceptFatal(notSupported) { bm0.filterRows(Array(0)) }
    TestUtils.interceptFatal(notSupported) { bm0.filterCols(Array(0)) }
  }
  
  @Test
  def testSparseBlockMatrixMath() {
    val lm = toLM(4, 4, Array(
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))

    val bm = toBM(lm, blockSize = 2)
    
    val keepArray = Array(
      Array.empty[Int],
      Array(0),
      Array(1, 3),
      Array(2, 3),
      Array(1, 2, 3),
      Array(0, 1, 2, 3))

    val lm_zero = BDM.zeros[Double](2, 2)

    def filterBlocks(keep: Array[Int]): BDM[Double] = {
      val flm = lm.copy
      (0 to 3).diff(keep).foreach { i =>
        val r = 2 * (i % 2)
        val c = 2 * (i / 2)
        flm(r to r + 1, c to c + 1) := lm_zero
      }
      flm
    }
    
    val transposeBI = Array(0 -> 0, 1 -> 2, 2 -> 1, 3 -> 3).toMap

    val v = Array(1.0, 2.0, 3.0, 4.0)
        
    // test transpose, diagonal, math ops
    for { keep <- keepArray } {
      val fbm = bm.filterBlocks(keep)
      val flm = filterBlocks(keep)

      assert(filteredEquals(fbm.transpose().transpose(), fbm))
      
      assert(filteredEquals(
        fbm.transpose(), bm.transpose().filterBlocks(keep.map(transposeBI).sorted)))

      assert(fbm.diagonal() sameElements diag(fbm.toBreezeMatrix()).toArray)

      assert(filteredEquals(+fbm, +bm.filterBlocks(keep)))
      assert(filteredEquals(-fbm, -bm.filterBlocks(keep)))
      
      assert(filteredEquals(fbm + fbm, (bm + bm).filterBlocks(keep)))
      assert(filteredEquals(fbm - fbm, (bm - bm).filterBlocks(keep)))
      assert(filteredEquals(fbm * fbm, (bm * bm).filterBlocks(keep)))

      assert(filteredEquals(fbm.rowVectorMul(v), bm.rowVectorMul(v).filterBlocks(keep)))
      assert(filteredEquals(fbm.rowVectorDiv(v), bm.rowVectorDiv(v).filterBlocks(keep)))

      assert(filteredEquals(fbm.colVectorMul(v), bm.colVectorMul(v).filterBlocks(keep)))
      assert(filteredEquals(fbm.colVectorDiv(v), bm.colVectorDiv(v).filterBlocks(keep)))
      
      assert(filteredEquals(fbm * 2, (bm * 2).filterBlocks(keep)))
      assert(filteredEquals(fbm / 2, (bm / 2).filterBlocks(keep)))
      
      assert(filteredEquals(fbm.sqrt(), bm.sqrt().filterBlocks(keep)))
      assert(filteredEquals(fbm.pow(3), bm.pow(3).filterBlocks(keep)))
      
      assert(fbm.dot(fbm).toBreezeMatrix() === flm * flm)
    }
    
    val bm0 = bm.filterBlocks(Array(0)).cache()
    val bm13 = bm.filterBlocks(Array(1, 3)).cache()
    val bm23 = bm.filterBlocks(Array(2, 3)).cache()
    val bm123 = bm.filterBlocks(Array(1, 2, 3)).cache()
        
    // test * with mismatched blocks
    assert(filteredEquals(bm0 * bm13, bm.filterBlocks(Array.empty[Int])))    
    assert(filteredEquals(bm13 * bm23, (bm * bm).filterBlocks(Array(3))))
    assert(filteredEquals(bm13 * bm, (bm * bm).filterBlocks(Array(1, 3))))
    assert(filteredEquals(bm * bm13, (bm * bm).filterBlocks(Array(1, 3))))
    
    // math ops not yet supported
    val blockMismatch: String = "requires block matrices to have the same set of blocks present"
    val notSupported: String = "not supported for block-filtered block matrices"

    TestUtils.interceptFatal(blockMismatch) { bm0 + bm13 }
    TestUtils.interceptFatal(blockMismatch) { bm123 + bm13 }
    TestUtils.interceptFatal(blockMismatch) { bm0 - bm13 }
    TestUtils.interceptFatal(blockMismatch) { bm123 - bm13 }

    TestUtils.interceptFatal(notSupported) { bm0.rowVectorAdd(v) }
    TestUtils.interceptFatal(notSupported) { bm0.colVectorAdd(v) }
    TestUtils.interceptFatal(notSupported) { bm0.rowVectorSub(v) }
    TestUtils.interceptFatal(notSupported) { bm0.colVectorSub(v) }
    TestUtils.interceptFatal(notSupported) { bm0.reverseRowVectorSub(v) }
    TestUtils.interceptFatal(notSupported) { bm0.reverseColVectorSub(v) }

    TestUtils.interceptFatal(notSupported) { bm0 + 2 }
    TestUtils.interceptFatal(notSupported) { bm0 - 2 }
    TestUtils.interceptFatal(notSupported) { 2 - bm0 }

    // most division ops not supported
    val v0 = Array(0.0, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    
    TestUtils.interceptFatal(notSupported) { bm0 / bm0 }
    TestUtils.interceptFatal(notSupported) { bm0.reverseRowVectorDiv(v) }
    TestUtils.interceptFatal(notSupported) { bm0.reverseColVectorDiv(v) }
    TestUtils.interceptFatal(notSupported) { 1 / bm0 }
    
    TestUtils.interceptFatal(notSupported) { bm0.rowVectorDiv(v0) }
    TestUtils.interceptFatal(notSupported) { bm0.colVectorDiv(v0) }
    TestUtils.interceptFatal("cannot divide block matrix by scalar 0.0") { bm0 / 0 }
    
    // exponent to negative power not supported
    TestUtils.interceptFatal(notSupported) { bm0.pow(-1)}
  }
  
  @Test
  def testFilterRowIntervals() {
    val lm = toLM(4, 4, Array(
      1, 2, 3, 4,
      5, 6, 7, 8,
      9, 10, 11, 12,
      13, 14, 15, 16))
    
    val bm = toBM(lm, blockSize = 2)

    val starts = Array[Long](1, 0, 0, 2)
    val stops = Array[Long](4, 4, 0, 3)

    val filteredLM = toLM(4, 4, Array(
      1, 2, 3, 4,
      5, 6, 7, 8,
      0, 0, 11, 12,
      0, 0, 15, 16))
    
    val zeroedLM = toLM(4, 4, Array(
      0, 2, 3, 4,
      5, 6, 7, 8,
      0, 0, 0, 0,
      0, 0, 15, 0))
    
    assert(bm.filterRowIntervals(starts, stops, blocksOnly = true).toBreezeMatrix() === filteredLM)
    assert(bm.filterRowIntervals(starts, stops, blocksOnly = false).toBreezeMatrix() === zeroedLM)
    
    val starts2 = Array[Long](0, 1, 2, 3)
    val stops2 = Array[Long](1, 2, 3, 4)

    val filteredLM2 = toLM(4, 4, Array(
      1, 2, 0, 0,
      5, 6, 0, 0,
      0, 0, 11, 12,
      0, 0, 15, 16))
    
    val zeroedLM2 = toLM(4, 4, Array(
      1, 0, 0, 0,
      0, 6, 0, 0,
      0, 0, 11, 0,
      0, 0, 0, 16))
    
    assert(bm.filterRowIntervals(starts2, stops2, blocksOnly = true).toBreezeMatrix() === filteredLM2)
    assert(bm.filterRowIntervals(starts2, stops2, blocksOnly = false).toBreezeMatrix() === zeroedLM2)
  }
}
