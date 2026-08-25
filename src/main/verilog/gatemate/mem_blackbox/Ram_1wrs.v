`timescale 1ns/1ps

module Ram_1wrs #(
        parameter integer wordCount = 0,
        parameter integer wordWidth = 0,
        parameter readUnderWrite = "dontCare", // Not implemented
        parameter duringWrite = "dontCare", // Not implemented
        parameter technology = "auto", // Not implemented
        parameter integer maskWidth = 0,
        parameter maskEnable = 1'b1, // Not implemented
        parameter integer addressWidth = $clog2(wordCount)
    )(
        input wire clk,
        input wire en,
        input wire wr,
        input wire [addressWidth-1:0] addr,
        input wire [maskWidth-1:0] mask,
        input wire [wordWidth-1:0] wrData,
        output wire [wordWidth-1:0] rdData,
    );


    reg [wordWidth-1:0] ram_block [wordCount-1:0];
    integer i;
    localparam COL_WIDTH = wordWidth/maskWidth;
    always @ (posedge clk) begin
        if(wr) begin
            for(i=0; i < maskWidth; i = i+1) begin
                if(mask[i]) begin
                    ram_block[addr][i*COL_WIDTH +: COL_WIDTH] <= wrData[i*COL_WIDTH +:COL_WIDTH];
                end
            end
        end
    end

    reg [wordWidth-1:0] ram_rd_data;
    always @ (posedge clk) begin
        if(en) begin
            ram_rd_data <= ram_block[addr];
        end
    end
    assign rdData = ram_rd_data;
endmodule
