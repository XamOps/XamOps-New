import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { DashboardRegion } from '../../../../types/dspmPages';

// The real map fetches the world outline from a CDN; the stub keeps each dot's position and click.
vi.mock('react-simple-maps', () => ({
  ComposableMap: ({ children }: { children: React.ReactNode }) => <svg data-testid="map">{children}</svg>,
  ZoomableGroup: ({ children, zoom }: { children: React.ReactNode; zoom: number }) => (
    <g data-testid="zoom-group" data-zoom={zoom}>{children}</g>
  ),
  Geographies: () => null,
  Geography: () => null,
  Marker: ({ children, coordinates, onClick }: {
    children: React.ReactNode; coordinates: [number, number]; onClick: () => void;
  }) => <g data-testid="marker" data-coordinates={coordinates.join(',')} onClick={onClick}>{children}</g>,
}));

import ActiveSensitiveRegions, { SENSITIVE_REGIONS_TOOLTIP, regionDatastoresLink } from '../ActiveSensitiveRegions';
import { regionCoordinates, regionName } from '../regionCatalog';

/** Invented regions and counts. */
const REGIONS: DashboardRegion[] = [
  { region: 'eu-west-3', datastores: 6, scannedDatastores: 4, sensitiveRecords: 1_250, sensitiveLabels: ['Financial', 'Personal'] },
  { region: 'swedencentral', datastores: 3, scannedDatastores: 0, sensitiveRecords: 40, sensitiveLabels: ['Health'] },
  { region: 'europe-north1', datastores: 2, scannedDatastores: 2, sensitiveRecords: 0, sensitiveLabels: [] },
  { region: 'us', datastores: 1, scannedDatastores: 1, sensitiveRecords: 0, sensitiveLabels: [] },
];

function renderWidget(regions: DashboardRegion[] = REGIONS, partial = false) {
  return render(
    <MemoryRouter>
      <ActiveSensitiveRegions regions={regions} partial={partial} />
    </MemoryRouter>,
  );
}

const widget = () => screen.getByRole('region', { name: 'Active Sensitive Regions' });
const regionCard = (name: string) => screen.getByRole('group', { name: `${name} region` });
const stat = (card: HTMLElement, label: string) => within(card).getByText(label).nextElementSibling as HTMLElement;

describe('regionCatalog', () => {
  it('names AWS and Azure regions with the bundle label and keeps any other code', () => {
    expect(regionName('us-east-1')).toBe('N. Virginia');
    expect(regionName('ap-south-2')).toBe('Hyderabad');
    expect(regionName('westeurope')).toBe('West Europe');
    expect(regionName('us-east1')).toBe('us-east1');
    expect(regionName('not-a-region')).toBe('not-a-region');
  });

  it('places known regions as [longitude, latitude] and nothing else', () => {
    expect(regionCoordinates('eu-west-3')).toEqual([2.35, 48.86]);
    expect(regionCoordinates('us')).toBeNull();
    expect(regionCoordinates('not-a-region')).toBeNull();
  });
});

describe('ActiveSensitiveRegions', () => {
  it('shows the title, the region count and the console tooltip', () => {
    renderWidget();
    expect(within(widget()).getByText('(Showing 4 Regions)')).toBeInTheDocument();
    expect(within(widget()).getByRole('img', { name: SENSITIVE_REGIONS_TOOLTIP })).toBeInTheDocument();
  });

  it('lists a tab per region in response order, labelled from the catalogue, the first selected', () => {
    renderWidget();
    const tabs = within(widget()).getAllByRole('tab');
    expect(tabs.map(tab => tab.textContent)).toEqual(['Paris', 'Sweden Central', 'europe-north1', 'us']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
  });

  it("shows the selected region's counts, the scanned share and its sensitive categories", () => {
    renderWidget();
    const card = regionCard('Paris');
    expect(stat(card, 'Total DataStores')).toHaveTextContent('6');
    expect(stat(card, 'Scanned DataStores')).toHaveTextContent('4 (67%)');
    expect(stat(card, 'Total Records')).toHaveTextContent('1.3K');
    expect(stat(card, 'Total Records')).toHaveAttribute('title', '1,250');
    expect(within(card).getByText('Sensitives')).toBeInTheDocument();
    expect(within(card).getByText('Financial')).toBeInTheDocument();
    expect(within(card).getByText('Personal')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'View All datastores in Paris' }))
      .toHaveAttribute('href', '/xamlens/datastores/cloud?region=eu-west-3');
    expect(within(card).queryByText('Total File Size')).toBeNull();
  });

  it('switches the card from a tab and from a map dot', async () => {
    const user = userEvent.setup();
    renderWidget();
    await user.click(within(widget()).getByRole('tab', { name: 'Sweden Central' }));
    expect(stat(regionCard('Sweden Central'), 'Scanned DataStores')).toHaveTextContent('0 (0%)');
    const dot = screen.getAllByTestId('marker').find(marker => within(marker).queryByText('europe-north1'))!;
    fireEvent.click(dot);
    expect(within(widget()).getByRole('tab', { name: 'europe-north1' })).toHaveAttribute('aria-selected', 'true');
    // No categories: no Sensitives row and no View All.
    expect(within(regionCard('europe-north1')).queryByText('Sensitives')).toBeNull();
    expect(within(regionCard('europe-north1')).queryByRole('link')).toBeNull();
  });

  it('draws a dot only for regions the catalogue can place', () => {
    renderWidget();
    expect(screen.getAllByTestId('marker').map(marker => marker.getAttribute('data-coordinates'))).toEqual([
      '2.35,48.86', '17.14,60.67', '27.2,60.57',
    ]);
  });

  it('zooms from 200% in steps of 50% between 100% and 400%', async () => {
    const user = userEvent.setup();
    renderWidget();
    expect(within(widget()).getByText('200%')).toBeInTheDocument();
    const zoomIn = within(widget()).getByRole('button', { name: 'Zoom in' });
    const zoomOut = within(widget()).getByRole('button', { name: 'Zoom out' });
    for (let i = 0; i < 4; i += 1) await user.click(zoomIn);
    expect(within(widget()).getByText('400%')).toBeInTheDocument();
    expect(screen.getByTestId('zoom-group')).toHaveAttribute('data-zoom', '4');
    expect(zoomIn).toBeDisabled();
    for (let i = 0; i < 6; i += 1) await user.click(zoomOut);
    expect(within(widget()).getByText('100%')).toBeInTheDocument();
    expect(zoomOut).toBeDisabled();
  });

  it('says so when no region is active', () => {
    renderWidget([]);
    expect(within(widget()).getByText('(Showing 0 Regions)')).toBeInTheDocument();
    expect(within(widget()).getByText('No active sensitive regions found')).toBeInTheDocument();
    expect(screen.queryByTestId('map')).toBeNull();
  });

  it('notes capped cloud rows', () => {
    renderWidget(REGIONS, true);
    expect(within(widget()).getByText('Based on the first 1,000 cloud datastores.')).toBeInTheDocument();
  });

  it('encodes the region in the View All link', () => {
    expect(regionDatastoresLink('a b&c')).toBe('/xamlens/datastores/cloud?region=a%20b%26c');
  });
});
