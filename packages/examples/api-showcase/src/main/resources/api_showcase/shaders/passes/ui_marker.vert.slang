struct VertexOutput {
    float4 position : SV_Position;
};

[shader("vertex")]
VertexOutput main(uint vertexIndex : SV_VertexID) {
    const float2 positions[3] = {
        float2(-0.94, -0.94),
        float2(-0.82, -0.94),
        float2(-0.94, -0.78)
    };
    VertexOutput output;
    output.position = float4(positions[vertexIndex], 0.0, 1.0);
    return output;
}
