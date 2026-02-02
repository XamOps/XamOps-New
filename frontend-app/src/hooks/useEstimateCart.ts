/**
 * Custom hook for managing the estimate cart
 * Handles localStorage persistence and cart operations
 */

import { useState, useEffect, useCallback } from 'react';
import type { EstimateCartItem } from '../types/calculator';

const STORAGE_KEY = 'xamops_calc_cart';

export const useEstimateCart = () => {
  const [cart, setCart] = useState<EstimateCartItem[]>([]);

  // Load cart from localStorage on mount
  useEffect(() => {
    const savedCart = localStorage.getItem(STORAGE_KEY);
    if (savedCart) {
      try {
        const parsedCart = JSON.parse(savedCart);
        setCart(parsedCart);
      } catch (error) {
        console.error('Failed to parse saved cart:', error);
        localStorage.removeItem(STORAGE_KEY);
      }
    }
  }, []);

  // Save cart to localStorage whenever it changes
  const saveCart = useCallback((newCart: EstimateCartItem[]) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(newCart));
    setCart(newCart);
  }, []);

  // Add item to cart
  const addToCart = useCallback((item: Omit<EstimateCartItem, 'id'>) => {
    const newItem: EstimateCartItem = {
      ...item,
      id: Date.now(),
    };
    const newCart = [...cart, newItem];
    saveCart(newCart);
  }, [cart, saveCart]);

  // Remove item from cart
  const removeFromCart = useCallback((id: number) => {
    const newCart = cart.filter(item => item.id !== id);
    saveCart(newCart);
  }, [cart, saveCart]);

  // Clear entire cart
  const clearCart = useCallback(() => {
    saveCart([]);
  }, [saveCart]);

  // Calculate total cost
  const totalCost = cart.reduce((sum, item) => sum + item.monthlyCost, 0);

  return {
    cart,
    totalCost,
    addToCart,
    removeFromCart,
    clearCart,
  };
};
