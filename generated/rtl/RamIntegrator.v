// Generator : SpinalHDL v1.12.3    git head : 591e64062329e5e2e2b81f4d52422948053edb97
// Component : RamIntegrator
// Git hash  : eada11bf86d74480390c397ce91771a8d8e27aa0

`timescale 1ns/1ps

module RamIntegrator (
  output wire          io_output_valid,
  output wire [15:0]   io_output_payload,
  input  wire          io_input_valid,
  input  wire [15:0]   io_input_payload,
  input  wire          clk,
  input  wire          reset
);

  reg        [15:0]   mem_spinal_port0;
  reg        [15:0]   mem_spinal_port1;
  reg        [15:0]   mem_spinal_port2;
  wire       [1:0]    _zz_mem_port;
  wire                _zz_mem_port_1;
  wire       [1:0]    _zz_stageOld_0;
  wire                _zz_stageOld_0_1;
  wire       [1:0]    _zz_mem_port_2;
  wire                _zz_mem_port_3;
  wire       [1:0]    _zz_stageOld_1;
  wire                _zz_stageOld_1_1;
  wire       [1:0]    _zz_mem_port_4;
  wire                _zz_mem_port_5;
  wire       [1:0]    _zz_stageOld_2;
  wire                _zz_stageOld_2_1;
  wire       [1:0]    _zz_mem_port_6;
  wire       [15:0]   _zz_mem_port_7;
  wire       [1:0]    _zz_mem_port_8;
  wire       [15:0]   _zz_mem_port_9;
  wire       [1:0]    _zz_mem_port_10;
  wire       [15:0]   _zz_mem_port_11;
  reg                 _zz_1;
  reg                 _zz_2;
  reg                 _zz_3;
  wire       [15:0]   stageOld_0;
  wire       [15:0]   stageOld_1;
  wire       [15:0]   stageOld_2;
  wire       [15:0]   stageNew_0;
  wire       [15:0]   stageNew_1;
  wire       [15:0]   stageNew_2;
  wire       [15:0]   inExt;
  reg [15:0] mem [0:2];

  assign _zz_stageOld_0 = 2'b00;
  assign _zz_stageOld_0_1 = 1'b1;
  assign _zz_stageOld_1 = 2'b01;
  assign _zz_stageOld_1_1 = 1'b1;
  assign _zz_stageOld_2 = 2'b10;
  assign _zz_stageOld_2_1 = 1'b1;
  assign _zz_mem_port_6 = 2'b00;
  assign _zz_mem_port_7 = stageNew_0;
  assign _zz_mem_port_8 = 2'b01;
  assign _zz_mem_port_9 = stageNew_1;
  assign _zz_mem_port_10 = 2'b10;
  assign _zz_mem_port_11 = stageNew_2;
  always @(posedge clk) begin
    if(_zz_stageOld_0_1) begin
      mem_spinal_port0 <= mem[_zz_stageOld_0];
    end
  end

  always @(posedge clk) begin
    if(_zz_stageOld_1_1) begin
      mem_spinal_port1 <= mem[_zz_stageOld_1];
    end
  end

  always @(posedge clk) begin
    if(_zz_stageOld_2_1) begin
      mem_spinal_port2 <= mem[_zz_stageOld_2];
    end
  end

  always @(posedge clk) begin
    if(_zz_3) begin
      mem[_zz_mem_port_6] <= _zz_mem_port_7;
    end
  end

  always @(posedge clk) begin
    if(_zz_2) begin
      mem[_zz_mem_port_8] <= _zz_mem_port_9;
    end
  end

  always @(posedge clk) begin
    if(_zz_1) begin
      mem[_zz_mem_port_10] <= _zz_mem_port_11;
    end
  end

  always @(*) begin
    _zz_1 = 1'b0;
    if(io_input_valid) begin
      _zz_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_2 = 1'b0;
    if(io_input_valid) begin
      _zz_2 = 1'b1;
    end
  end

  always @(*) begin
    _zz_3 = 1'b0;
    if(io_input_valid) begin
      _zz_3 = 1'b1;
    end
  end

  assign stageOld_0 = mem_spinal_port0;
  assign stageOld_1 = mem_spinal_port1;
  assign stageOld_2 = mem_spinal_port2;
  assign inExt = io_input_payload;
  assign stageNew_0 = ($signed(stageOld_0) + $signed(inExt));
  assign stageNew_1 = ($signed(stageNew_0) + $signed(stageOld_1));
  assign stageNew_2 = ($signed(stageNew_1) + $signed(stageOld_2));
  assign io_output_payload = stageNew_2;
  assign io_output_valid = io_input_valid;

endmodule
