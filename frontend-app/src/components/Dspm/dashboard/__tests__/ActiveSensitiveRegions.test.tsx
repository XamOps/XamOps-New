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
import { groupRegions, regionCoordinates, regionName } from '../regionCatalog';

/**
 * Invented regions and counts, in the backend's order (AWS, GCP, Azure). GCP `europe-west9` is in Paris
 * like AWS `eu-west-3`, so the two share one tab, as the console's place-keyed regions do.
 */
const REGIONS: DashboardRegion[] = [
  { region: 'eu-west-3', datastores: 6, scannedDatastores: 4, sensitiveLabels: ['Financial', 'Personal'] },
  { region: 'ca-central-1', datastores: 3, scannedDatastores: 0, sensitiveLabels: ['Health'] },
  { region: 'europe-west9', datastores: 2, scannedDatastores: 1, sensitiveLabels: ['Personal', 'IT & Security'] },
  { region: 'europe', datastores: 1, scannedDatastores: 1, sensitiveLabels: [] },
  { region: 'swedencentral', datastores: 2, scannedDatastores: 0, sensitiveLabels: [] },
  { region: 'not-a-region', datastores: 1, scannedDatastores: 0, sensitiveLabels: [] },
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
  it("names regions as the console's place-keyed regions do, and keeps any other code", () => {
    // The console's names for the codes checked against it in build 8.
    expect(regionName('us-east-1')).toBe('N. Virginia');
    expect(regionName('ap-south-1')).toBe('Mumbai');
    expect(regionName('ap-south-2')).toBe('Hyderabad');
    expect(regionName('us-east1')).toBe('South Carolina');
    expect(regionName('asia-south1')).toBe('Mumbai');
    expect(regionName('us')).toBe('USA');
    expect(regionName('westeurope')).toBe('Amsterdam');
    expect(regionName('not-a-region')).toBe('not-a-region');
  });

  it('places regions as [longitude, latitude], the GCP multi-regions at the middle of their area', () => {
    expect(regionCoordinates('us-east-1')).toEqual([-77.49, 39.04]);
    expect(regionCoordinates('ap-south-1')).toEqual([72.88, 19.08]);
    expect(regionCoordinates('ap-south-2')).toEqual([78.49, 17.39]);
    expect(regionCoordinates('us-east1')).toEqual([-80.01, 33.2]);
    expect(regionCoordinates('us')).toEqual([-98.58, 39.83]);
    expect(regionCoordinates('westeurope')).toEqual([4.9, 52.37]);
    expect(regionCoordinates('not-a-region')).toBeNull();
  });

  it('merges codes with one name where the first of them is, summing counts and keeping each label once', () => {
    expect(groupRegions(REGIONS)).toEqual([
      {
        id: 'eu-west-3', name: 'Paris', codes: ['eu-west-3', 'europe-west9'], datastores: 8, scannedDatastores: 5,
        sensitiveLabels: ['Financial', 'Personal', 'IT & Security'], coordinates: [2.35, 48.86],
      },
      {
        id: 'ca-central-1', name: 'Central', codes: ['ca-central-1'], datastores: 3, scannedDatastores: 0,
        sensitiveLabels: ['Health'], coordinates: [-73.57, 45.5],
      },
      { id: 'europe', name: 'Europe', codes: ['europe'], datastores: 1, scannedDatastores: 1, sensitiveLabels: [], coordinates: [15.26, 54.53] },
      { id: 'swedencentral', name: 'Gävle', codes: ['swedencentral'], datastores: 2, scannedDatastores: 0, sensitiveLabels: [], coordinates: [17.14, 60.67] },
      { id: 'not-a-region', name: 'not-a-region', codes: ['not-a-region'], datastores: 1, scannedDatastores: 0, sensitiveLabels: [], coordinates: null },
    ]);
  });
});

describe('ActiveSensitiveRegions', () => {
  it('shows the title, the count of named regions and the console tooltip', () => {
    renderWidget();
    expect(within(widget()).getByText('(Showing 5 Regions)')).toBeInTheDocument();
    expect(within(widget()).getByRole('img', { name: SENSITIVE_REGIONS_TOOLTIP })).toBeInTheDocument();
  });

  it('lists a tab per region name in response order, the first selected', () => {
    renderWidget();
    const tabs = within(widget()).getAllByRole('tab');
    expect(tabs.map(tab => tab.textContent)).toEqual(['Paris', 'Central', 'Europe', 'Gävle', 'not-a-region']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
  });

  it("shows the selected tab's summed counts, the scanned share and its sensitive categories", () => {
    renderWidget();
    const card = regionCard('Paris');
    expect(stat(card, 'Total DataStores')).toHaveTextContent('8');
    expect(stat(card, 'Scanned DataStores')).toHaveTextContent('5 (63%)');
    expect(within(card).getByText('Sensitives')).toBeInTheDocument();
    expect(within(card).getAllByText(/^(Financial|Personal|IT & Security)$/).map(chip => chip.textContent))
      .toEqual(['Financial', 'Personal', 'IT & Security']);
    expect(within(card).getByRole('link', { name: 'View All datastores in Paris' }))
      .toHaveAttribute('href', '/xamlens/datastores/cloud?region=eu-west-3%2Ceurope-west9');
    // Left out: the rows carry neither (GAP-19).
    expect(within(card).queryByText('Total File Size')).toBeNull();
    expect(within(card).queryByText('Total Records')).toBeNull();
  });

  it('switches the card from a tab and from a map dot', async () => {
    const user = userEvent.setup();
    renderWidget();
    await user.click(within(widget()).getByRole('tab', { name: 'Central' }));
    expect(stat(regionCard('Central'), 'Scanned DataStores')).toHaveTextContent('0 (0%)');
    const dot = screen.getAllByTestId('marker').find(marker => within(marker).queryByText('Europe'))!;
    fireEvent.click(dot);
    expect(within(widget()).getByRole('tab', { name: 'Europe' })).toHaveAttribute('aria-selected', 'true');
    expect(stat(regionCard('Europe'), 'Scanned DataStores')).toHaveTextContent('1 (100%)');
    // No categories: no Sensitives row and no View All.
    expect(within(regionCard('Europe')).queryByText('Sensitives')).toBeNull();
    expect(within(regionCard('Europe')).queryByRole('link')).toBeNull();
  });

  it('draws one dot per tab the catalogue can place, at the first placed code', () => {
    renderWidget();
    expect(screen.getAllByTestId('marker').map(marker => marker.getAttribute('data-coordinates'))).toEqual([
      '2.35,48.86', '-73.57,45.5', '15.26,54.53', '17.14,60.67',
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

  it('encodes the region codes in the View All link', () => {
    expect(regionDatastoresLink(['a b&c', 'd'])).toBe('/xamlens/datastores/cloud?region=a%20b%26c%2Cd');
  });
});
