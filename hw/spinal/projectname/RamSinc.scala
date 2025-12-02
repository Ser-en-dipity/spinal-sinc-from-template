package projectname

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.sim._

case class MyRamIntegrator(width: Int, order: Int) extends Component {
  val io = new Bundle {
    val input = slave(Flow(SInt(width bits)))
    val output = master(Flow(SInt(width bits)))
  }
  
  // 共享 RAM 存储 order 个积分器的累加值
  val sumRam = Mem(SInt(width bits), wordCount = order)
  sumRam.init(Seq.fill(order)(S(0, width bits)))
  
  // 地址位宽
  val addrWidth = log2Up(order)
  
  // 延迟输入和valid一个周期，对齐 readSync 延迟
  val inputDelay = RegNext(io.input.payload) init(S(0, width bits))
  val validDelay = RegNext(io.input.valid) init(False)
  
  // 发起所有 readSync 读取
  val readValues = (0 until order).map { i =>
    sumRam.readSync(U(i, addrWidth bits))
  }
  
  // 使用延迟后的输入和 readSync 读出的值计算
  var currentValue = inputDelay
  
  for (i <- 0 until order) {
    val newSum = currentValue + readValues(i)
    // 只在 validDelay 为真时写入
    when(validDelay) {
      sumRam.write(U(i, addrWidth bits), newSum)
    }
    currentValue = newSum
  }
  
  io.output.valid := validDelay
  io.output.payload := currentValue
}

case class MyRamDecimator(dr: Int, outputWidth: Int) extends Component {
  val io = new Bundle {
    val output = Vec(master(Flow(SInt(outputWidth bits))), dr)
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  
  // 创建 DR 个并行的抽取器，每个偏移 1 个时钟周期
  val counters = Vec(Reg(UInt(log2Up(dr) bits)) init(0), dr)
  
  // 用 Mem 代替 dataRegs
  val dataMem = Mem(SInt(outputWidth bits), wordCount = dr)
  dataMem.init(Seq.fill(dr)(S(0, outputWidth bits)))
  
  val addrWidth = log2Up(dr)
  
  // 初始化每个计数器的偏移
  for (i <- 0 until dr) {
    counters(i).init(i)
  }
  
  // 每个抽取器独立工作
  for (i <- 0 until dr) {
    // 从 Mem 读取数据
    io.output(i).payload := dataMem.readAsync(U(i, addrWidth bits))
    io.output(i).valid := False
    
    when(io.input.valid) {
      when(counters(i) === (dr - 1)) {
        // 写入 Mem
        dataMem.write(U(i, addrWidth bits), io.input.payload)
        io.output(i).valid := True
        counters(i) := 0
      } otherwise {
        counters(i) := counters(i) + 1
      }
    }
  }
}

case class MyRamComb(outputWidth: Int, order: Int) extends Component {
  val io = new Bundle {
    val output = master(Flow(SInt(outputWidth bits)))
    val input = slave(Flow(SInt(outputWidth bits)))
  }
  
  // 共享 RAM 存储 order 个差分器的数据
  val dataRam = Mem(SInt(outputWidth bits), wordCount = order)
  dataRam.init(Seq.fill(order)(S(0, outputWidth bits)))
  
  // 地址位宽
  val addrWidth = log2Up(order)
  
  // 存储 order 个差分器的中间结果和输出
  var currentValue = io.input.payload
  var currentValid = io.input.valid
  
  // 依次处理 order 个差分器
  for (i <- 0 until order) {
    // readSync 读取第 i 个差分器的上一次值
    val prevData = dataRam.readSync(U(i, addrWidth bits))
    
    // 计算差值
    val diff = currentValue - prevData
    
    // 写入当前值到第 i 个地址
    dataRam.write(U(i, addrWidth bits), currentValue, enable = currentValid)
    
    // 更新为下一级的输入
    currentValue = Mux(currentValid, diff, S(0, outputWidth bits))
  }
  
  // 最终输出
  io.output.valid := currentValid
  io.output.payload := currentValue
}

case class MyRamSinc(sincGenerics: SincGenerics) extends Component {
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
  val integrator = MyRamIntegrator(rWidth, sincGenerics.order)
  val decimator = MyRamDecimator(sincGenerics.dr, rWidth)
  
  // 创建 DR 个 MyRamComb，每个处理一个 decimator 输出
  val combs = (0 until sincGenerics.dr).map { _ =>
    MyRamComb(rWidth, sincGenerics.order)
  }.toList

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
  // 连接积分器
  integrator.io.input.valid := io.input.valid
  integrator.io.input.payload := io.input.payload.asSInt.resize(rWidth)
  
  // 连接抽取器
  decimator.io.input <> integrator.io.output
  
  // 连接每个 decimator 输出到对应的 comb
  combs.zipWithIndex.foreach { case (comb, i) =>
    comb.io.input <> decimator.io.output(i)
  }
  
  // 合并所有 comb 的输出
  // 使用优先级编码器选择第一个有效的输出
  val combOutputs = Vec(combs.map(_.io.output.payload))
  val combValids = Vec(combs.map(_.io.output.valid))
  
  // 找到第一个有效的输出（优先级编码）
  val selectedPayload = SInt(rWidth bits)
  val selectedValid = Bool()
  
  selectedPayload := S(0, rWidth bits)
  selectedValid := False
  
  for (i <- (sincGenerics.dr - 1) downto 0) {
    when(combValids(i)) {
      selectedPayload := combOutputs(i)
      selectedValid := True
    }
  }
  
  val dec_rate = sincGenerics.dr
  var raw = selectedPayload + S((1 << outputWidth - 1) - 1, rWidth bits)
  val min_val = -S((1 << outputWidth - 1), rWidth bits)
  val diff3 = Mux(raw < min_val, min_val, raw)
  io.output.valid := selectedValid
  io.output.payload := diff3(outputWidth - 1 downto 0)

}

object MyRamSincTest extends App {
  // 测试 PWM 正弦波输入
  SimConfig.withWave.compile(new MyRamSinc(SincGenerics(dr = 32, order = 3))).doSim { dut =>
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
    val totalSamples = 200000 // 总采样点数
    val signalFreq = 125000.0

    val modulator = IdealSigmaDeltaModulator(order = 2, ref = 1.0)
    modulator.reset()

    // 创建文件写入器，只记录有效输出
    import java.io.PrintWriter
    val writer = new PrintWriter("simWorkspace/MyRamSinc/valid_output.txt")
    writer.println("# sample_index,output_value")
    
    var validCount = 0

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

      // 读取输出 - 只记录 valid=true 的数据
      val output = dut.io.output.payload.toInt
      val valid = dut.io.output.valid.toBoolean
      val clkDiv = dut.io.clk_divided.toBoolean

      if (valid) {
        writer.println(s"$validCount,$output")
        validCount += 1
      }
    }

    writer.close()

    println("=" * 60)
    println("仿真完成! VCD 波形文件已生成")
    println(s"有效输出已保存到: simWorkspace/MyRamSinc/valid_output.txt")
    println(s"有效输出数量: $validCount")
    println("=" * 60)
  }
}
