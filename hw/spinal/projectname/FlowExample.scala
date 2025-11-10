package projectname


import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.sim._

case class Integrator(outputWidth: Int) extends Component {
    val io = new Bundle {
        val output = master(Flow(SInt(outputWidth bits)))
        val input = slave(Flow(SInt(outputWidth bits)))
    }
    io.output.valid := io.input.valid
    val accum = Reg(SInt(outputWidth bits)) init(0)
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
    val data_reg = Reg(SInt(outputWidth bits)) init(0)
    val counter = Reg(UInt(32 bits)) init(0)

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
    val delay = Reg(SInt(outputWidth bits)) init(0)
    val output_reg = Reg(SInt(outputWidth bits)) init(0)

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
case class FractionNDivider() extends Component {
    val io = new Bundle {
        val clk_out = out Bool()
    }
    
    val clk_divided = Reg(Bool()) init(False)
    val cnt = Reg(UInt(32 bits)) init(0)
    
    io.clk_out := clk_divided
    
    when(cnt < 2) {
        cnt := cnt + 1
    } otherwise {
        cnt := 0
        clk_divided := ~clk_divided
    }
}
case class Sampler() extends  Component {
    val io = new Bundle {
        val clk_in = in Bool()
        val data_in = in Bool()
        val data_out = out Bool()
    }

    val sample0 = Reg(Bool()) init(False)
    val sample1 = Reg(Bool()) init(False)
    val sample2 = Reg(Bool()) init(False)
    val cnt = Reg(UInt(3 bits)) init(0)
    
    // 用125MHz主时钟进行6次采样（cnt: 0->1->2->3->4->5->0...）
    // 关键：先判断再递增，否则采样时机错误
    when(cnt === 0) {
        sample0 := io.data_in
        cnt := 1
    } elsewhen (cnt === 1) {
        cnt := 2
    } elsewhen (cnt === 2) {
        sample1 := io.data_in
        cnt := 3
    } elsewhen (cnt === 3) {
        cnt := 4
    } elsewhen (cnt === 4) {
        sample2 := io.data_in
        cnt := 5
    } otherwise {  // cnt === 5
        cnt := 0  // 重置，开始新一轮采样
    }
    
    // 简单多数投票 (需要2位宽度来表示0-3)
    // 先扩展到足够宽度再相加，避免溢出
    val sum = sample0.asUInt.resize(2) + sample1.asUInt.resize(2) + sample2.asUInt.resize(2)
    val voted = sum >= U(2, 2 bits)
    
    // 用寄存器输出，避免时序问题
    val data_out_reg = Reg(Bool()) init(False)
    data_out_reg := voted
    io.data_out := data_out_reg

}

case class MySinc2(sincGenerics: SincGenerics) extends Component {
    val outputWidth = 2 + sincGenerics.order *  log2Up(sincGenerics.dr) // output width = input width + order * log2(DR) , input width = 1, sign width 1 bit
    val io = new Bundle {
        val input = slave(Flow(Bool()))  // PWM 使用 Bool 类型
        val output = master(Flow(SInt(outputWidth bits)))
        val clk_divided = out Bool()  // 分频后的时钟输出
    }
    val integrators = List.fill(sincGenerics.order)(Integrator(outputWidth))
    val decimator = Decimator(sincGenerics.dr, outputWidth)
    val combs = List.fill(sincGenerics.order)(Comb(outputWidth))
    
    // 实例化分频器
    val clockDivider = FractionNDivider()
    io.clk_divided := clockDivider.io.clk_out

    // 连接流水线组件
    integrators.zipWithIndex.foreach { case (integrator, i) =>
        if (i == 0) {
            // 第一个积分器：需要扩展 Bool 输入到 outputWidth
            integrator.io.input.valid := io.input.valid
            integrator.io.input.payload := io.input.payload.asSInt.resize(outputWidth)
        } else {
            integrator.io.input <> integrators(i - 1).io.output
        }
    }
    decimator.io.input <> integrators.last.io.output
    combs.zipWithIndex.foreach { case (comb, i) =>
        comb.io.input <> (if (i == 0) decimator.io.output else combs(i - 1).io.output)
    }
    io.output <> combs.last.io.output

}

object SamplerTest extends App {
  // 测试 Sampler 模块
  SimConfig.withWave.compile(new Sampler()).doSim { dut =>
    import scala.math._
    import scala.util.Random
    
    // 启动主时钟 125 MHz
    dut.clockDomain.forkStimulus(period = 8000) // 8000ps = 8ns = 125 MHz
    
    // 初始化输入
    dut.io.clk_in #= false
    dut.io.data_in #= false
    
    // 等待复位完成
    dut.clockDomain.waitRisingEdge()
    
    println("=" * 60)
    println("测试 Sampler 模块")
    println("主时钟: 125 MHz (8ns)")
    println("输入时钟: 20 MHz (50ns)")
    println("采样策略: 3次采样 + 多数投票")
    println("=" * 60)
    
    val random = new Random()
    val mainClkPeriod = 8.0      // ns (125 MHz)
    val inputClkFreq = 20000000.0 // 20 MHz
    val inputClkPeriod = 50.0     // ns (20 MHz)
    val totalSamples = 1000       // 测试样本数
    
    // 生成测试数据：交替的比特流和随机噪声
    var clkPhase = 0.0
    var dataPhase = 0
    var lastClk = false
    
    for (sample <- 0 until totalSamples) {
      val t = sample * mainClkPeriod  // 当前时间 (ns)
      
      // 生成 20MHz 输入时钟
      val clkValue = ((t % inputClkPeriod) < (inputClkPeriod / 2))
      
      // 检测时钟上升沿，在上升沿改变数据
      val clkRisingEdge = clkValue && !lastClk
      if (clkRisingEdge) {
        // 每个时钟周期切换数据模式
        dataPhase = (dataPhase + 1) % 8
      }
      lastClk = clkValue
      
      // 生成测试数据模式
      val idealData = dataPhase match {
        case 0 | 1 => true   // 连续的1
        case 2 | 3 => false  // 连续的0
        case 4 => true       // 交替模式
        case 5 => false
        case 6 => random.nextBoolean()  // 随机数据
        case 7 => random.nextBoolean()
      }
      
      // 添加噪声（10%概率翻转）
      val noisyData = if (random.nextDouble() < 0.1) !idealData else idealData
      
      // 设置输入
      dut.io.clk_in #= clkValue
      dut.io.data_in #= noisyData
      
      // 等待时钟
      dut.clockDomain.waitRisingEdge()
      
      // 读取输出
      val output = dut.io.data_out.toBoolean
      
      // 每50个样本打印一次状态
      if (sample % 50 == 0 || clkRisingEdge) {
        println(f"样本 $sample%4d: 时间=${t}%.1fns, " +
                f"clk_in=$clkValue%5s, data_in=$noisyData%5s (理想=$idealData%5s), " +
                f"data_out=$output%5s" + 
                (if (clkRisingEdge) " ← 时钟上升沿" else ""))
      }
      
      // 进度指示
      if (sample % 200 == 0 && sample > 0) {
        println(s"  [进度] 已处理 $sample 个样本...")
      }
    }
    
    println("=" * 60)
    println("Sampler 模块测试完成! VCD 波形文件已生成")
    println("=" * 60)
  }
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
    dut.io.input.payload #= false  // 初始化为0
    
    // 等待复位完成
    dut.clockDomain.waitRisingEdge()
    
    println("=" * 60)
    println("测试 PWM 正弦波")
    println("信号: 50*sin(400*π*t), PWM频率100kHz")
    println("SINC滤波器: DR=8, Order=3, Fs_out=250kHz")
    println("=" * 60)
    
    val random = new Random()
    val samplingFreq = 2000000.0  // 2 MHz 采样频率
    val signalFreq = 200.0        // 200 Hz 信号频率
    val amplitude = 50.0
    val pwmFreq = 100000.0        // 100 kHz PWM 频率（每周期20个采样点）
    val totalSamples = 50000      // 总采样点数 (25ms数据)
    val noiseLevel = 0.05         // 5% 噪声
    val DR = 8                    // 抽取率 (与硬件参数一致)
    
    // 模拟 PWM 输入
    for (sample <- 0 until totalSamples) {
      val t = sample.toDouble / samplingFreq  // 转换为 Double
      
      // 生成正弦波值 + 噪声
      val sineValue = sin(2 * Pi * signalFreq * t)
      val noise = random.nextGaussian() * noiseLevel * amplitude
      // val signalWithNoise = sineValue + noise
      val signalWithNoise = sineValue  // 暂时不加噪声
      
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
        val gain = 512.0  // DR^Order = 8^3 = 512
        val scaledOutput = output / gain
        println(f"样本 $sample%6d: 时间=${t*1000}%.3fms, 正弦=$sineInt%4d, 输出=$output%8d, 缩放=$scaledOutput%6.2f, clkDiv=$clkDiv")
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
