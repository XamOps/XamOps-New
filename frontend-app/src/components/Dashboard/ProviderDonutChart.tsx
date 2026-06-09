/**
 * ProviderDonutChart Component
 *
 * Transformed into an Advanced Polar Area Chart
 * Visualizes provider distribution with radial segments (Variable radius = value)
 */

import React, { useEffect, useRef } from 'react';
import { Chart, registerables, TooltipModel } from 'chart.js';
import type { ProviderData } from '../../types/dashboard';

// Register Chart.js components
Chart.register(...registerables);

interface ProviderDonutChartProps {
  awsData: ProviderData | null;
  gcpData: ProviderData | null;
  azureData: ProviderData | null;
}

export const ProviderDonutChart: React.FC<ProviderDonutChartProps> = ({ awsData, gcpData, azureData }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current) return;

    const ctx = canvasRef.current.getContext('2d');
    if (!ctx) return;

    // Destroy existing chart
    if (chartRef.current) {
      chartRef.current.destroy();
    }

    const awsSpend = awsData?.selectedAccount?.monthToDateSpend || 0;
    const gcpSpend = gcpData?.selectedAccount?.monthToDateSpend || 0;
    const azureSpend = azureData?.selectedAccount?.monthToDateSpend || 0;
    const totalSpend = awsSpend + gcpSpend + azureSpend;

    // Create Gradients for 3D effect
    const createGradient = (colorStart: string, colorMid: string, colorEnd: string) => {
      const gradient = ctx.createLinearGradient(0, 0, 300, 300);
      gradient.addColorStop(0, colorStart);
      gradient.addColorStop(0.5, colorMid);
      gradient.addColorStop(1, colorEnd);
      return gradient;
    };

    const awsGradient = createGradient('#F97316', '#FB923C', '#FDBA74'); // Orange 500 -> 300
    const gcpGradient = createGradient('#059669', '#34D399', '#6EE7B7'); // Emerald 600 -> 300
    const azureGradient = createGradient('#2563EB', '#60A5FA', '#93C5FD'); // Blue 600 -> 300


    // External Tooltip Handler (Glassmorphism)
    const getOrCreateTooltip = (chart: Chart) => {
      let tooltipEl = chart.canvas.parentNode?.querySelector('div#chartjs-polar-tooltip') as HTMLElement;

      if (!tooltipEl) {
        tooltipEl = document.createElement('div');
        tooltipEl.id = 'chartjs-polar-tooltip';
        tooltipEl.style.background = 'rgba(255, 255, 255, 0.85)';
        tooltipEl.style.backdropFilter = 'blur(12px)';
        tooltipEl.style.borderRadius = '12px';
        tooltipEl.style.color = '#1e293b';
        tooltipEl.style.opacity = '0';
        tooltipEl.style.pointerEvents = 'none';
        tooltipEl.style.position = 'absolute';
        tooltipEl.style.transform = 'translate(-50%, 0)';
        tooltipEl.style.transition = 'all .1s ease';
        tooltipEl.style.boxShadow = '0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05)';
        tooltipEl.style.border = '1px solid rgba(255, 255, 255, 0.5)';
        tooltipEl.style.padding = '10px 14px';
        tooltipEl.style.zIndex = '100';

        const table = document.createElement('table');
        table.style.margin = '0px';

        tooltipEl.appendChild(table);
        chart.canvas.parentNode?.appendChild(tooltipEl);
      }

      return tooltipEl;
    };

    const externalTooltipHandler = (context: { chart: Chart; tooltip: TooltipModel<any> }) => {
      const { chart, tooltip } = context;
      const tooltipEl = getOrCreateTooltip(chart);

      if (tooltip.opacity === 0) {
        tooltipEl.style.opacity = '0';
        return;
      }

      if (tooltip.body) {
        const bodyLines = tooltip.body.map((b: any) => b.lines);
        const tableBody = document.createElement('tbody');

        bodyLines.forEach((_body: string, i: number) => {
          const colors = tooltip.labelColors[i];
          const dataIndex = tooltip.dataPoints[0].dataIndex;
          const dataset = chart.data.datasets[0];
          const value = dataset.data[dataIndex] as number;
          const percentage = ((value / totalSpend) * 100).toFixed(1);
          const label = chart.data.labels?.[dataIndex] as string;

          const tr = document.createElement('tr');
          const td = document.createElement('td');
          td.style.display = 'flex';
          td.style.flexDirection = 'column';
          td.style.alignItems = 'center';

          const labelSpan = document.createElement('span');
          labelSpan.innerText = label;
          labelSpan.style.fontSize = '12px';
          labelSpan.style.color = '#64748b';
          labelSpan.style.marginBottom = '2px';
          labelSpan.style.fontWeight = '600';
          labelSpan.style.textTransform = 'uppercase';
          labelSpan.style.letterSpacing = '0.05em';

          const valueSpan = document.createElement('span');
          valueSpan.innerText = `$${value.toLocaleString()}`;
          valueSpan.style.fontSize = '16px';
          valueSpan.style.fontWeight = '700';
          valueSpan.style.color = '#1e293b';

          const percentSpan = document.createElement('span');
          percentSpan.innerText = `${percentage}% Share`;
          percentSpan.style.fontSize = '10px';
          percentSpan.style.padding = '2px 8px';
          percentSpan.style.borderRadius = '99px';
          percentSpan.style.marginTop = '4px';
          percentSpan.style.backgroundColor = (colors.backgroundColor as string);
          percentSpan.style.color = '#ffffff';
          percentSpan.style.fontWeight = '700';
          percentSpan.style.textShadow = '0 1px 2px rgba(0,0,0,0.2)';

          td.appendChild(labelSpan);
          td.appendChild(valueSpan);
          td.appendChild(percentSpan);
          tr.appendChild(td);
          tableBody.appendChild(tr);
        });

        const tableRoot = tooltipEl.querySelector('table');
        while (tableRoot?.firstChild) tableRoot.firstChild.remove();
        tableRoot?.appendChild(tableBody);
      }

      const { offsetLeft: positionX, offsetTop: positionY } = chart.canvas;
      tooltipEl.style.opacity = '1';
      tooltipEl.style.left = positionX + tooltip.caretX + 'px';
      tooltipEl.style.top = positionY + tooltip.caretY + 'px';
    };

    // Plugin to display total spend in the center of the doughnut chart
    const centerTextPlugin = {
      id: 'centerText',
      beforeDraw(chart: Chart) {
        const { ctx, height, chartArea } = chart;
        ctx.restore();

        // Calculate the actual center of the chart area (excluding legend)
        const centerX = (chartArea.left + chartArea.right) / 2;
        const centerY = (chartArea.top + chartArea.bottom) / 2;

        const fontSize = (height / 170).toFixed(2);
        ctx.font = `bolder ${fontSize}em 'Plus Jakarta Sans', sans-serif`;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'center';

        const text = `$${Math.round(totalSpend).toLocaleString()}`;

        ctx.fillStyle = '#1e293b';
        ctx.fillText(text, centerX, centerY - 10);

        ctx.font = `normal ${parseFloat(fontSize) * 0.35}em 'Plus Jakarta Sans', sans-serif`;
        ctx.fillStyle = '#64748b';
        const subText = 'Total Spend';
        ctx.fillText(subText, centerX, centerY + 15);

        ctx.textAlign = 'left'; // Reset alignment
        ctx.save();
      }
    };


    // Create chart
    chartRef.current = new Chart(ctx, {
      type: 'doughnut', // Switched back to Doughnut for better visibility of small segments
      data: {
        labels: ['AWS', 'GCP', 'Azure'],
        datasets: [
          {
            data: [awsSpend, gcpSpend, azureSpend],
            backgroundColor: [awsGradient, gcpGradient, azureGradient],
            borderWidth: 0,
            hoverOffset: 15,
            borderRadius: 20, // Rounded ends for premium feel
            spacing: 5,       // Space between segments
            cutout: '75%',    // Sleek thin ring
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: {
          animateRotate: true,
          animateScale: true,
          duration: 1500,
          easing: 'easeOutQuart',
        },
        layout: {
          padding: 20
        },
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 20,
              font: {
                family: "'Plus Jakarta Sans', sans-serif",
                size: 11,
                weight: '600',
              },
              color: '#64748b',
              generateLabels: function (chart: Chart) {
                const data = chart.data;
                if (data.labels && data.labels.length && data.datasets.length) {
                  return data.labels.map((label: unknown, i: number) => {
                    const value = data.datasets[0].data[i] as number;
                    const total = (data.datasets[0].data as number[]).reduce((a, b) => a + b, 0);
                    const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0';
                    return {
                      text: `${label} (${percentage}%)`,
                      fillStyle: (data.datasets[0].backgroundColor as any[])[i]?.stops?.[0] || (data.datasets[0].backgroundColor as string[])[i],
                      hidden: false,
                      index: i,
                      strokeStyle: 'transparent',
                      fontColor: '#475569'
                    };
                  });
                }
                return [];
              }
            },
          },
          tooltip: {
            enabled: false,
            external: externalTooltipHandler
          },
        },
      },
      plugins: [centerTextPlugin],
    });

    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
      }
      const tooltipEl = document.querySelector('div#chartjs-polar-tooltip');
      if (tooltipEl) tooltipEl.remove();
    };
  }, [awsData, gcpData, azureData]);

  return (
    <div className="glass-card rounded-2xl p-8 flex flex-col border-t-0 shadow-lg hover:shadow-xl transition-shadow duration-300">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-xl font-bold text-slate-800 flex items-center gap-3">
            <span className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.04)] flex items-center justify-center text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)]">
              <i className="fas fa-chart-pie"></i>
            </span>
            Provider Spending
          </h3>
          <p className="text-sm text-slate-500 mt-1.5 ml-[52px] font-medium">Spending breakdown by cloud provider</p>
        </div>
      </div>
      <div className="relative w-full flex items-center justify-center py-4 flex-1 min-h-[300px]" >
        <canvas ref={canvasRef}></canvas>
      </div>
    </div>
  );
};
