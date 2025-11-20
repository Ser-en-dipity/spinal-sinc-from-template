package projectname

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.sim._
import spinal.lib.bus.tilelink.fabric.MasterBus

case class RamIntegrator(outputWidth: Int, order: Int) extends Component {
  val io = new Bundle {
    val output = master(Flow(SInt(outputWidth bits)))
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  // 单片 RAM（SInt，便于加法），同步读：上一拍发起，本拍可用
  val addrW = log2Up(order)
  val mem = Mem(SInt(outputWidth bits), wordCount = order)

  // 读出所有级的旧值：每拍都读，避免依赖 valid 作为读使能造成对齐复杂
  val stageOld = Vec(SInt(outputWidth bits), order)
  if (order == 1) {
    stageOld(0) := mem.readSync(0, enable = True)
  } else {
    for (i <- 0 until order) {
      stageOld(i) := mem.readSync(address = U(i, addrW bits), enable = True)
    }
  }

  // 组合前缀和（分组累加）：G0=old(0)+x，Gi=Gi-1+old(i)
  val stageNew = Vec(SInt(outputWidth bits), order)
  val inExt = io.input.payload.resize(outputWidth)
  stageNew(0) := stageOld(0) + inExt
  for (i <- 1 until order) {
    stageNew(i) := stageNew(i - 1) + stageOld(i)
  }

  // 仅在输入有效时写回所有级的新状态
  when(io.input.valid) {
    if (order == 1) {
      mem.write(0, stageNew(0))
    } else {
      for (i <- 0 until order) {
        mem.write(address = U(i, addrW bits), data = stageNew(i))
      }
    }
  }

  // 输出：组合输出最后一级结果；valid 透传
  io.output.payload := stageNew(order - 1)
  io.output.valid := io.input.valid
}
case class RamDecimator(dr: Int, outputWidth: Int) extends Component {
  val io = new Bundle {
    val o_data = out(SInt(outputWidth bits))
    val o_valid = out(Bool())
    val i_data = in(SInt(outputWidth bits))
    val i_valid = in(Bool())
  }
  val ram = Mem(SInt(outputWidth bits), wordCount = 1)
  val counter = Reg(UInt(32 bits)) init (0)

  io.o_valid := False
  io.o_data := ram.readSync(0)

  when(io.i_valid) {
    when(counter === (dr - 1)) {
      ram.write(0, io.i_data)
      io.o_valid := True
      counter := 0
    } otherwise {
      counter := counter + 1
    }
  }
}
case class RamComb(outputWidth: Int) extends Component {
  val io = new Bundle {
    val o_data = out(SInt(outputWidth bits))
    val o_valid = out(Bool())
    val i_data = in(SInt(outputWidth bits))
    val i_valid = in(Bool())
  }
  io.o_valid := io.i_valid
  val ram = Mem(SInt(outputWidth bits), wordCount = 1)
  val data_reg = Reg(SInt(outputWidth bits)) init (0)

  when(io.i_valid) {
    val previous = ram.readSync(0)
    data_reg := io.i_data - previous
    ram.write(0, io.i_data)
  }
  io.o_data := data_reg
}

case class MyRamSinc2(sincGenerics: SincGenerics) extends Component {
  val outputWidth =
    2 + sincGenerics.order * log2Up(
      sincGenerics.dr
    ) // output width = input width + order * log2(DR) , input width = 1, sign width 1 bit
  val io = new Bundle {
    val input = slave(Flow(Bool())) // PWM 使用 Bool 类型
    val output = master(Flow(SInt(outputWidth bits)))
    // 系统默认125Mhz 无需输入
    // val samplingTick = in Bool()  // 采样时钟输入 125M
    val clk_divided = out Bool () // 分频后的时钟输出 20.83M
  }
  val integrators = List.fill(sincGenerics.order)(RamIntegrator(outputWidth, sincGenerics.order))
  val decimator = RamDecimator(sincGenerics.dr, outputWidth)
  val combs = List.fill(sincGenerics.order)(RamComb(outputWidth))

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

}

object MyRamSincTest extends App {
  // 测试 PWM 正弦波输入
  SimConfig.withWave.compile(new MyRamSinc2(SincGenerics(dr = 8, order = 3))).doSim { dut =>
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

    println("=" * 60)
    println("测试 PWM 正弦波")
    println("信号: 50*sin(400*π*t), PWM频率100kHz")
    println("SINC滤波器: DR=8, Order=3, Fs_out=250kHz")
    println("=" * 60)

    val random = new Random()
    val samplingFreq = 2000000.0 // 2 MHz 采样频率
    val signalFreq = 200.0 // 200 Hz 信号频率
    val amplitude = 50.0
    val pwmFreq = 100000.0 // 100 kHz PWM 频率（每周期20个采样点）
    val totalSamples = 50000 // 总采样点数 (25ms数据)
    val noiseLevel = 0.05 // 5% 噪声
    val DR = 8 // 抽取率 (与硬件参数一致)

    // 模拟 PWM 输入
    for (sample <- 0 until totalSamples) {
      val t = sample.toDouble / samplingFreq // 转换为 Double

      // 生成正弦波值 + 噪声
      val sineValue = sin(2 * Pi * signalFreq * t)
      val noise = random.nextGaussian() * noiseLevel * amplitude
      val signalWithNoise = sineValue + noise
      //   val signalWithNoise = sineValue  // 暂时不加噪声

      // 将模拟值转换为 PWM (归一化到 0-1)
      var duty = amplitude * (sineValue + 1)
      if (duty < 0) duty = 0
      else if (duty > 2 * amplitude) duty = 2 * amplitude

      val car_phase = pwmFreq * t
      val pwmThreshold = car_phase - floor(car_phase)
      val pwm = 2 * amplitude * pwmThreshold

      // PWM 比较: 如果归一化值大于阈值则输出1，否则0
      val pwmBit = if (duty > pwm) true else false

      // 将正弦波值转换为整数（用于调试显示）
      val sineInt = (amplitude * sineValue).toInt

      // 输入到设计
      dut.io.input.valid #= true
      dut.io.input.payload #= pwmBit

      // 等待时钟
      dut.clockDomain.waitRisingEdge()

      // 读取输出
      val output = dut.io.output.payload.toInt
      val valid = dut.io.output.valid.toBoolean
      val clkDiv = dut.io.clk_divided.toBoolean

      // 只在valid=true时打印（有效的抽取输出）
      if (valid) {
        val gain = 512.0 // DR^Order = 8^3 = 512
        val scaledOutput = output / gain
        println(
          f"样本 $sample%6d: 时间=${t * 1000}%.3fms, 正弦=$sineInt%4d, 输出=$output%8d, 缩放=$scaledOutput%6.2f, clkDiv=$clkDiv"
        )
      }

      // 进度指示
      if (sample % 10000 == 0 && sample > 0) {
        println(s"  [进度] 已处理 $sample 个样本...")
      }
    }

    println("=" * 60)
    println("仿真完成! VCD 波形文件已生成")
    println("=" * 60)
  }
}

// 简单单元仿真：仅测试 RamIntegrator，输入 1..5，观测输出与有效对齐
object RamIntegratorTest extends App {
  SimConfig.withWave.compile(RamIntegrator(outputWidth = 16, order = 3)).doSim { dut =>
    // 时钟设置：8ns -> 125MHz
    dut.clockDomain.forkStimulus(period = 8000)

    // 初始化
    dut.io.input.valid #= false
    dut.io.input.payload #= 0
    dut.clockDomain.waitSampling()

    def stepDrive(value: Int): Unit = {
      dut.io.input.valid #= true
      dut.io.input.payload #= value
      dut.clockDomain.waitRisingEdge()
      // 采样输出
      val out = dut.io.output.payload.toInt
      val vld = dut.io.output.valid.toBoolean
      println(f"in=$value%3d  ->  valid=$vld%-5s out=$out%8d")
    }

    println("---- Feed 1..5 ----")
    (1 to 5).foreach(stepDrive)

    // 再喂几拍 0，观察积分器输出衰减/维持情况（取决于实现）
    println("---- Hold 0 for 5 cycles ----")
    (0 until 5).foreach(_ => stepDrive(0))

    // 结束
    dut.io.input.valid #= false
    dut.clockDomain.waitRisingEdge()
  }
}

// 生成 RamIntegrator 的 Verilog 代码示例
object RamIntegratorVerilog extends App {
  // 可根据需要调整输出目录 / 名称 / 频率等配置
  Config.spinal.generateVerilog(new RamIntegrator(outputWidth = 32, order = 3))
}
