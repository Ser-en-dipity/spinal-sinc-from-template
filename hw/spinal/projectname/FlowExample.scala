package projectname

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.sim._

case class IdealSigmaDeltaModulator(val order: Int = 2, val ref: BigDecimal = 1.0) {
  private var integral_values = List.fill(order)(BigDecimal(0.0))
  private def integrate_once(din: BigDecimal, fed: BigDecimal, stageIndex: Int): BigDecimal = {
    val diff: BigDecimal = stageIndex match {
      case 0 => din - fed
      case _ => integrate_once(din, fed, stageIndex - 1) - fed
    }
    val ret: BigDecimal = integral_values(stageIndex) + diff
    integral_values = integral_values.updated(stageIndex, ret)
    ret
  }
  private def integrate(din: BigDecimal, fed: BigDecimal): BigDecimal = { integrate_once(din, fed, order - 1) }
  def process(din: BigDecimal): Boolean = {
    val fed = if (integral_values.last >= 0) ref else -ref
    val result = integrate(din, fed)
    result >= 0
  }
  def reset(): Unit = { integral_values = List.fill(order)(0.0) }
}

case class Integrator(outputWidth: Int) extends Component {
  val io = new Bundle {
    val output = master(Flow(SInt(outputWidth bits)))
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  io.output.valid := io.input.valid
  val accum = Reg(SInt(outputWidth bits)) init (0)
  when(io.input.valid) {
    accum := accum + io.input.payload
  }
  io.output.payload := accum
}
case class Decimator(dr: Int, outputWidth: Int) extends Component {
  val io = new Bundle {
    val output = master(Flow(SInt(outputWidth bits)))
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  val data_reg = Reg(SInt(outputWidth bits)) init (0)
  val counter = Reg(UInt(32 bits)) init (0)

  io.output.valid := False
  io.output.payload := data_reg

  when(io.input.valid) {
    when(counter === (dr - 1)) {
      data_reg := io.input.payload
      io.output.valid := True
      counter := 0
    } otherwise {
      counter := counter + 1
    }
  }
}
case class Comb(outputWidth: Int) extends Component {
  val io = new Bundle {
    val output = master(Flow(SInt(outputWidth bits)))
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  io.output.valid := io.input.valid
  val delay = Reg(SInt(outputWidth bits)) init (0)
  val output_reg = Reg(SInt(outputWidth bits)) init (0)

  io.output.payload := output_reg

  when(io.input.valid) {
    output_reg := io.input.payload - delay
    delay := io.input.payload
  }
}
case class SincGenerics(
    val dr: Int = 32,
    val order: Int = 3,
    val outputWidthOrDefault: Int = 0,
    val maxOrder: Int = 6
)

case class MySinc2(sincGenerics: SincGenerics) extends Component {
  val outputWidth =
    sincGenerics.order * log2Up(
      sincGenerics.dr
    ) // output width = input width + order * log2(DR)
  val io = new Bundle {
    val input = slave(Flow(Bool())) // PWM 使用 Bool 类型
    val output = master(Flow(SInt(outputWidth bits)))
    // 系统默认125Mhz 无需输入
    // val samplingTick = in Bool()  // 采样时钟输入 125M
    val clk_divided = out Bool () // 分频后的时钟输出 20.83M
  }
  val rWidth = outputWidth + 2
  val integrators = List.fill(sincGenerics.order)(Integrator(rWidth))
  val decimator = Decimator(sincGenerics.dr, rWidth)
  val combs = List.fill(sincGenerics.order)(Comb(rWidth))

  // 分频
  val clockDivider = new Area {
    val tick = Reg(Bool()) init (False)
    val counter = Reg(UInt(3 bits)) init (0)

    when(counter < 2) {
      counter := counter + 1
    } otherwise {
      counter := 0
      tick := ~tick
    }
  }
  io.clk_divided := clockDivider.tick

  // 采样
  val sampler = new Area {
    val sampleLength = 3 // 可以改成参数
    val synchroniser = BufferCC.withTag(io.input.payload, init = False)
    val samples = History(that = synchroniser, length = sampleLength, when = io.clk_divided, init = True)
    // 将Vec[Data]转换为IndexedSeq[Bool]供MajorityVote使用
    // 动态构建：遍历samples的所有元素并转换类型
    // val samplesSeq   = (0 until sampleLength).map(i => samples(i).asInstanceOf[Bool])
    val value = RegNext(MajorityVote(samples)) init (True)
    val tick = RegNext(io.clk_divided) init (False)
  }

  // 连接流水线组件
  integrators.zipWithIndex.foreach { case (integrator, i) =>
    if (i == 0) {
      // 第一个积分器：需要扩展 Bool 输入到 outputWidth
      //   integrator.io.input.valid := sampler.tick
      //   integrator.io.input.payload := Mux(sampler.value, S(1, outputWidth bits), S(-1, outputWidth bits))
      integrator.io.input.valid := io.input.valid
      integrator.io.input.payload := io.input.payload.asSInt.resize(rWidth)
    } else {
      integrator.io.input <> integrators(i - 1).io.output
    }
  }
  decimator.io.input <> integrators.last.io.output
  combs.zipWithIndex.foreach { case (comb, i) =>
    comb.io.input <> (if (i == 0) decimator.io.output else combs(i - 1).io.output)
  }
  val dec_rate = sincGenerics.dr
  var raw = combs.last.io.output.payload + S((1 << outputWidth - 1) - 1, rWidth bits)
  val min_val = -S((1 << outputWidth - 1), rWidth bits)
  val diff3 = Mux(raw < min_val, min_val, raw)
  io.output.valid := combs.last.io.output.valid
  io.output.payload := diff3(outputWidth - 1 downto 0)

}

object MySincTest extends App {
  // 测试 PWM 正弦波输入
  SimConfig.withWave.compile(new MySinc2(SincGenerics(dr = 32, order = 3))).doSim { dut =>
    import scala.math._
    import scala.util.Random

    // 启动时钟和复位
    // Verilator 的 VCD timescale 是 1ps
    // period = 8000 表示 8000ps = 8ns = 125 MHz
    dut.clockDomain.forkStimulus(period = 8000)

    // 初始化输入
    dut.io.input.valid #= true
    dut.io.input.payload #= false // 初始化为0

    // 等待复位完成
    dut.clockDomain.waitRisingEdge()

    // 将激励时间基准与DUT时钟一致：125 MHz
    val samplingFreq = 20000000.0 // 20 MHz 采样频率 这也是输入给modulator的时钟
    val totalSamples = 200000 // 总采样点数
    val signalFreq = 150000.0

    val modulator = IdealSigmaDeltaModulator(order = 2, ref = 1.0)
    modulator.reset()

    // 模拟 PWM 输入
    for (sample <- 0 until totalSamples) {
      val t = sample * 8e-9

      dut.io.input.valid #= sample % 6 == 0
      if (sample % 6 == 0) {
        val sineValue = 0.9 * sin(2 * Pi * signalFreq * t)
        val bit = modulator.process(sineValue)

        // 输入到设计
        dut.io.input.payload #= bit
      }

      // 等待时钟
      dut.clockDomain.waitSampling()

      // 读取输出
      val output = dut.io.output.payload.toInt
      val valid = dut.io.output.valid.toBoolean
      val clkDiv = dut.io.clk_divided.toBoolean

    }

    println("=" * 60)
    println("仿真完成! VCD 波形文件已生成")
    println("=" * 60)
  }
}
