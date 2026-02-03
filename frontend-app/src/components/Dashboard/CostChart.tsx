/**
 * CostChart Component
 *
 * Advanced Gradient Area Line Graph showing cost history across AWS, GCP, and Azure
 * Features smooth curves (spline), rich gradient fills, and glassmorphism tooltips
 */

import React, { useEffect, useRef } from 'react';
import { Chart, registerables, TooltipModel } from 'chart.js';
import type { ProviderData } from '../../types/dashboard';

// Register Chart.js components
Chart.register(...registerables);

interface CostChartProps {
  awsData: ProviderData | null;
  gcpData: ProviderData | null;
  azureData: ProviderData | null;
}

export const CostChart: React.FC<CostChartProps> = ({ awsData, gcpData, azureData }) => {
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

    // Get labels
    let labels: string[] = [];
    if (awsData?.selectedAccount?.costHistory?.labels) {
      labels = awsData.selectedAccount.costHistory.labels;
    } else if (gcpData?.selectedAccount?.costHistory?.labels) {
      labels = gcpData.selectedAccount.costHistory.labels;
    } else if (azureData?.selectedAccount?.costHistory?.labels) {
      labels = azureData.selectedAccount.costHistory.labels;
    }

    if (labels.length === 0) {
      labels = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'];
    }

    // Helper to get costs
    const getCosts = (data: ProviderData | null) => {
      if (!data?.selectedAccount?.costHistory?.costs) {
        return new Array(labels.length).fill(0);
      }
      return data.selectedAccount.costHistory.costs;
    };

    // Create Premium Gradients (Top to Bottom Fade)
    const createAreaGradient = (colorRGB: string) => {
      const gradient = ctx.createLinearGradient(0, 0, 0, 400);
      gradient.addColorStop(0, `rgba(${colorRGB}, 0.5)`);   // High opacity at top
      gradient.addColorStop(1, `rgba(${colorRGB}, 0.0)`);   // Fade to transparent
      return gradient;
    };

    const createStrokeGradient = (colorStart: string, colorEnd: string) => {
      const gradient = ctx.createLinearGradient(0, 0, 400, 0); // Horizontal gradient for the line itself
      gradient.addColorStop(0, colorStart);
      gradient.addColorStop(1, colorEnd);
      return gradient;
    };

    // AWS: Orange
    const awsFill = createAreaGradient('249, 115, 22');
    const awsStroke = createStrokeGradient('#F97316', '#FDBA74');

    // GCP: Emerald
    const gcpFill = createAreaGradient('16, 185, 129');
    const gcpStroke = createStrokeGradient('#10B981', '#6EE7B7');

    // Azure: Blue
    const azureFill = createAreaGradient('59, 130, 246');
    const azureStroke = createStrokeGradient('#3B82F6', '#93C5FD');

    // External Tooltip Handler
    const getOrCreateTooltip = (chart: Chart) => {
      let tooltipEl = chart.canvas.parentNode?.querySelector('div#chartjs-tooltip') as HTMLElement;

      if (!tooltipEl) {
        tooltipEl = document.createElement('div');
        tooltipEl.id = 'chartjs-tooltip';
        tooltipEl.style.background = 'rgba(255, 255, 255, 0.85)';
        tooltipEl.style.backdropFilter = 'blur(12px)';
        tooltipEl.style.borderRadius = '16px';
        tooltipEl.style.color = '#1e293b';
        tooltipEl.style.opacity = '0';
        tooltipEl.style.pointerEvents = 'none';
        tooltipEl.style.position = 'absolute';
        tooltipEl.style.transform = 'translate(-50%, 0)';
        tooltipEl.style.transition = 'all .2s cubic-bezier(0.16, 1, 0.3, 1)';
        tooltipEl.style.boxShadow = '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)';
        tooltipEl.style.border = '1px solid rgba(255, 255, 255, 0.6)';
        tooltipEl.style.padding = '16px';
        tooltipEl.style.zIndex = '100';
        tooltipEl.style.minWidth = '200px';

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
        const titleLines = tooltip.title || [];
        const bodyLines = tooltip.body.map((b: any) => b.lines);

        const tableHead = document.createElement('thead');
        titleLines.forEach((title: string) => {
          const tr = document.createElement('tr');
          tr.style.borderWidth = '0';
          const th = document.createElement('th');
          th.style.borderWidth = '0';
          th.style.textAlign = 'left';
          th.style.fontFamily = "'Plus Jakarta Sans', sans-serif";
          th.style.fontSize = '12px';
          th.style.fontWeight = '600';
          th.style.color = '#94a3b8';
          th.style.textTransform = 'uppercase';
          th.style.letterSpacing = '0.05em';
          th.style.paddingBottom = '8px';

          const text = document.createTextNode(title);
          th.appendChild(text);
          tr.appendChild(th);
          tableHead.appendChild(tr);
        });

        const tableBody = document.createElement('tbody');
        bodyLines.forEach((body: string, i: number) => {
          const colors = tooltip.labelColors[i];
          const span = document.createElement('span');
          span.style.background = typeof colors.borderColor === 'object' ? (colors.borderColor as any).stops?.[0] : colors.borderColor; // Use stroke color
          span.style.boxShadow = `0 0 10px ${typeof colors.borderColor === 'object' ? (colors.borderColor as any).stops?.[0] : colors.borderColor}`;
          span.style.marginRight = '12px';
          span.style.height = '8px';
          span.style.width = '8px';
          span.style.display = 'inline-block';
          span.style.borderRadius = '50%';

          const tr = document.createElement('tr');
          tr.style.backgroundColor = 'inherit';
          tr.style.borderWidth = '0';

          const td = document.createElement('td');
          td.style.borderWidth = '0';
          td.style.paddingTop = '8px';
          td.style.fontFamily = "'Plus Jakarta Sans', sans-serif";
          td.style.fontSize = '16px';
          td.style.fontWeight = '700';
          td.style.color = '#1e293b';
          td.style.display = 'flex';
          td.style.alignItems = 'center';

          const text = document.createTextNode(body as unknown as string);
          td.appendChild(span);
          td.appendChild(text);
          tr.appendChild(td);
          tableBody.appendChild(tr);
        });

        const tableRoot = tooltipEl.querySelector('table');
        while (tableRoot?.firstChild) tableRoot.firstChild.remove();
        tableRoot?.appendChild(tableHead);
        tableRoot?.appendChild(tableBody);
      }

      const { offsetLeft: positionX, offsetTop: positionY } = chart.canvas;
      tooltipEl.style.opacity = '1';
      tooltipEl.style.left = positionX + tooltip.caretX + 'px';
      tooltipEl.style.top = positionY + tooltip.caretY + 'px';
    };

    // Create chart
    chartRef.current = new Chart(ctx, {
      type: 'line', // Changed to Line
      data: {
        labels,
        datasets: [
          {
            label: 'AWS',
            data: getCosts(awsData),
            backgroundColor: awsFill,
            borderColor: awsStroke,
            borderWidth: 3,
            tension: 0.4, // Smooth curve
            fill: true,
            pointBackgroundColor: '#ffffff',
            pointBorderColor: '#F97316',
            pointBorderWidth: 2,
            pointRadius: 0, // Hidden by default
            pointHoverRadius: 6,
            pointHoverBackgroundColor: '#F97316',
            pointHoverBorderColor: '#ffffff',
            pointHoverBorderWidth: 3,
          },
          {
            label: 'GCP',
            data: getCosts(gcpData),
            backgroundColor: gcpFill,
            borderColor: gcpStroke,
            borderWidth: 3,
            tension: 0.4,
            fill: true,
            pointBackgroundColor: '#ffffff',
            pointBorderColor: '#10B981',
            pointBorderWidth: 2,
            pointRadius: 0,
            pointHoverRadius: 6,
            pointHoverBackgroundColor: '#10B981',
            pointHoverBorderColor: '#ffffff',
            pointHoverBorderWidth: 3,
          },
          {
            label: 'Azure',
            data: getCosts(azureData),
            backgroundColor: azureFill,
            borderColor: azureStroke,
            borderWidth: 3,
            tension: 0.4,
            fill: true,
            pointBackgroundColor: '#ffffff',
            pointBorderColor: '#3B82F6',
            pointBorderWidth: 2,
            pointRadius: 0,
            pointHoverRadius: 6,
            pointHoverBackgroundColor: '#3B82F6',
            pointHoverBorderColor: '#ffffff',
            pointHoverBorderWidth: 3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
          mode: 'index',
          intersect: false,
        },
        plugins: {
          legend: {
            position: 'top',
            align: 'end',
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
              boxWidth: 6,
              boxHeight: 6
            },
          },
          tooltip: {
            enabled: false,
            external: externalTooltipHandler,
          },
        },
        scales: {
          x: {
            grid: {
              display: false,
              drawBorder: false,
            },
            ticks: {
              font: {
                family: "'Plus Jakarta Sans', sans-serif",
                size: 11,
                weight: '500',
              },
              color: '#94a3b8',
              padding: 12,
            },
            border: { display: false }
          },
          y: {
            grid: {
              color: 'rgba(226, 232, 240, 0.4)',
              borderDash: [8, 8],
              drawBorder: false,
              drawTicks: false
            },
            ticks: {
              font: {
                family: "'Plus Jakarta Sans', sans-serif",
                size: 10,
                weight: '500',
              },
              color: '#cbd5e1',
              padding: 12,
              maxTicksLimit: 5,
              callback: function (value: number | string) {
                return '$' + Number(value).toLocaleString();
              },
            },
            border: { display: false }
          },
        },
      },
    });

    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
      }
      const tooltipEl = document.querySelector('div#chartjs-tooltip');
      if (tooltipEl) tooltipEl.remove();
    };
  }, [awsData, gcpData, azureData]);

  return (
    <div className="lg:col-span-2 glass-card rounded-2xl p-8 flex flex-col border-t-0 shadow-lg hover:shadow-xl transition-shadow duration-300">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h3 className="text-xl font-bold text-slate-800 flex items-center gap-3">
            <span className="w-10 h-10 rounded-xl bg-orange-50 flex items-center justify-center text-orange-500 shadow-sm border border-orange-100">
              <i className="fas fa-chart-line"></i>
            </span>
            Spend Velocity
          </h3>
          <p className="text-sm text-slate-500 mt-1.5 ml-[52px] font-medium">Real-time cost trajectory analysis</p>
        </div>
        <div className="flex gap-2">
          <button className="px-3 py-1.5 text-xs font-bold text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition shadow-sm">
            This Month
          </button>
        </div>
      </div>
      <div className="relative w-full flex-1 min-h-[350px]">
        <canvas ref={canvasRef}></canvas>
      </div>
    </div>
  );
};
